package org.wso2.carbon.uuf;

import io.netty.handler.codec.http.*;
import org.apache.commons.io.*;
import org.slf4j.*;
import org.wso2.carbon.uuf.core.*;
import org.wso2.carbon.uuf.core.create.*;
import org.wso2.msf4j.util.*;

import javax.ws.rs.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.wso2.carbon.uuf.core.create.Resolver.STATIC_RESOURCE_URI_PREFIX;

public class UUFRegistry {

    private static final Logger log = LoggerFactory.getLogger(UUFRegistry.class);
    private final AppCreator appCreator;
    private final Optional<DebugAppender> debugAppender;
    private final Map<String, App> apps = new HashMap<>();
    private Resolver resolver;
    private FileNameMap fileNameMap;

    public UUFRegistry(AppCreator appCreator, Optional<DebugAppender> debugAppender, Resolver resolver) {
        this.appCreator = appCreator;
        this.debugAppender = debugAppender;
        this.resolver = resolver;
    }

    public static Optional<DebugAppender> createDebugAppender() {
        String uufDebug = SystemVariableUtil.getValue("uufDebug", "false");
        if (uufDebug.equalsIgnoreCase("true")) {
            DebugAppender appender = new DebugAppender();
            appender.attach();
            return Optional.of(appender);
        } else {
            return Optional.empty();
        }
    }

    public Response.ResponseBuilder serve(HttpRequest request) {
        String uri = request.getUri().replaceAll("/+", "/");
        if (!uri.startsWith("/")) {
            uri = "/" + uri;
        }

        int firstSlash = uri.indexOf('/', 1);

        if (firstSlash < 0) {
            if (uri.equals("/favicon.ico")) {
                //TODO: send a favicon, cacheable favicon avoids frequent requests for it.
                return Response.status(404).entity("");
            }

            // eg: url = http://example.com/app and uri = /app
            // since we don't support ROOT app, this must be a mis-type
            return Response.status(301).entity("").header("Location", uri + "/");
        }

        String appName = uri.substring(1, firstSlash);
        String resourcePath = uri.substring(firstSlash, uri.length());

        if (log.isDebugEnabled() && !resourcePath.startsWith("/debug/")) {
            log.debug("request received " + request.getMethod() + " " + request.getUri() + " " + request
                    .getProtocolVersion());
        }

        App app = apps.get(appName);
        try {
            if (isStaticResourceRequest(resourcePath)) {
                Path resource = resolver.resolveStatic(appName, resourcePath);
                if (Files.exists(resource) && Files.isRegularFile(resource)) {
                    return Response.ok(resource.toFile(), getMime(resourcePath));
                } else {
                    return Response.status(Response.Status.NOT_FOUND).entity(
                            "Requested resource '" + uri + "' does not exists at '" + resource + "'");
                }
            } else {
                if (app == null || debugAppender.isPresent()) {
                    app = appCreator.createApp(appName, "/" + appName);
                    apps.put(appName, app);
                }
                if (resourcePath.equals("/debug/api/pages/")) {
                    //TODO: fix issues when same page is in multiple components
                    return Response.ok(app
                            .getComponents()
                            .entrySet()
                            .stream()
                            .flatMap(entry -> entry.getValue().getPages().stream())
                            .collect(Collectors.toSet()));
                }

//TODO:fix
//                if (resourcePath.startsWith("/debug/api/fragments/")) {
//                    return Response.ok(app
//                            .getComponents()
//                            .entrySet()
//                            .stream()
//                            .flatMap(entry -> entry.getValue().getFragments().values().stream())
//                            .collect(Collectors.toSet()));
//                }
                if (resourcePath.startsWith("/debug/logs")) {
                    if (debugAppender.isPresent()) {
                        return Response.ok(debugAppender.get().asJson(), "application/json");
                    } else {
                        return Response.status(Response.Status.GONE);
                    }
                }
                if (resourcePath.startsWith("/debug/")) {
                    if (resourcePath.endsWith("/")) {
                        resourcePath = resourcePath + "index.html";
                    }
                    InputStream resourceAsStream = this.getClass().getResourceAsStream("/apps" + resourcePath);
                    if (resourceAsStream != null) {
                        String debugContent = IOUtils.toString(
                                resourceAsStream,
                                "UTF-8");
                        return Response.ok(debugContent, getMime(resourcePath));
                    } else {
                        return Response.status(Response.Status.NOT_FOUND);
                    }
                }
                String page = app.renderPage(uri.substring(appName.length() + 1));
                return Response.ok(page).header("Content-Type", "text/html");
            }
            //TODO: Don't catch this Ex, move the logic below the 'instanceof' check
        } catch (UUFException e) {

            // https://googlewebmastercentral.blogspot.com/2010/04/to-slash-or-not-to-slash.html
            // if the tailing / is extra or a it is missing, send 301
            if (e.getStatus() == Response.Status.NOT_FOUND && app != null) {
                if (uri.endsWith("/")) {
                    String uriWithoutSlash = resourcePath.substring(0, resourcePath.length() - 1);
                    if (app.hasPage(uriWithoutSlash)) {
                        return Response.status(301).header("Location", uriWithoutSlash);
                    }
                } else {
                    String uriWithSlash = resourcePath + "/";
                    if (app.hasPage(uriWithSlash)) {
                        return Response.status(301).header("Location", uri + "/");
                    }
                }
            }

            return sendError(appName, e, e.getStatus());
        } catch (Exception e) {
            Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;
            Throwable cause = e.getCause();
            //TODO check this loop's logic
            while (cause != null) {
                if (cause instanceof UUFException) {
                    status = ((UUFException) cause).getStatus();
                    break;
                }
                if (cause == cause.getCause()) {
                    break;
                }
                cause = cause.getCause();
            }
            return sendError(appName, e, status);
        }
    }

    private String getMime(String resourcePath) {
        int extensionIndex = resourcePath.lastIndexOf(".");
        String extension = (extensionIndex == -1) ? resourcePath : resourcePath.substring(extensionIndex + 1,
                resourcePath.length());
        Optional<String> mime = MimeMapper.getMimeType(extension);
        return (mime.isPresent()) ? mime.get() : "text/html";
    }

    private boolean isStaticResourceRequest(String resourcePath) {
        return resourcePath.startsWith("/" + STATIC_RESOURCE_URI_PREFIX);
    }

    private Response.ResponseBuilder sendError(String appName, Exception e, Response.Status status) {
        log.error("error while serving context '" + appName + "'", e);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return Response.status(status).entity(sw.toString()).header("Content-Type", "text/plain");
    }
}
