/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.portlet;

import org.orbeon.oxf.servlet.OrbeonXFormsFilter;

import javax.portlet.*;
import javax.portlet.filter.*;
import java.io.*;

/**
 * The Orbeon portlet filter intercepts the output of a portlet, sets up information in the request and forwards the
 * request to the trampoline servlet for XForms rendering. 
 */
public class OrbeonPortletXFormsFilter implements RenderFilter, ResourceFilter {

    public static final String PATH_PARAMETER_NAME = "orbeon.path";

    public static final String PATH_TEMPLATE = "6b45488abd86954c28ce1a7ee10ab1b9193eb4dd"; // some unlikely value
    public static final String PORTLET_NAMESPACE_TEMPLATE_ATTRIBUTE = "oxf.xforms.renderer.portlet.namespace";
    public static final String PORTLET_RENDER_URL_TEMPLATE_ATTRIBUTE = "oxf.xforms.renderer.portlet.render-url";
    public static final String PORTLET_ACTION_URL_TEMPLATE_ATTRIBUTE = "oxf.xforms.renderer.portlet.action-url";
    public static final String PORTLET_RESOURCE_URL_TEMPLATE_ATTRIBUTE = "oxf.xforms.renderer.portlet.resource-url";
    public static final String PORTLET_METHOD_ATTRIBUTE = "oxf.xforms.renderer.portlet.method";
    public static final String PORTLET_PATH_QUERY_ATTRIBUTE = "oxf.xforms.renderer.portlet.path";

    public static final String TRAMPOLINE_PATH = "/xforms-trampoline";

//    public static final String RENDERER_CONTAINER_ATTRIBUTE_NAME = "oxf.xforms.renderer.container";
    protected static final String DEFAULT_ENCODING = "UTF-8"; // must be this per Portlet spec

    protected PortletContext portletContext;
    protected String applicationPathRegexp = "^/(ops/|config/|xbl/orbeon/|forms/orbeon/|apps/fr/|xforms-server).*$";

    public OrbeonPortletXFormsFilter() {}

    public void init(FilterConfig filterConfig) throws PortletException {
        this.portletContext = filterConfig.getPortletContext();

        // Allow overriding which resource paths are handled by the filter configuration
        final String applicationPathRegexp = filterConfig.getInitParameter(OrbeonXFormsFilter.RESOURCE_PATHS_PARAMETER_NAME);
        if (applicationPathRegexp != null)
            this.applicationPathRegexp = applicationPathRegexp;
    }

    /**
     * Filter render requests.
     */
    public void doFilter(RenderRequest renderRequest, RenderResponse renderResponse, FilterChain filterChain) throws IOException, PortletException {

        final FilterResponseWrapper responseWrapper = new FilterResponseWrapper(renderResponse);

        // Execute filter
        filterChain.doFilter(renderRequest, responseWrapper);

        // Set document if not present AND output was intercepted
        final boolean isEmptyContent;
        if (renderRequest.getAttribute(OrbeonXFormsFilter.RENDERER_DOCUMENT_ATTRIBUTE_NAME) == null) {
            final String content = responseWrapper.getContent();
            if (content != null) {
                renderRequest.setAttribute(OrbeonXFormsFilter.RENDERER_DOCUMENT_ATTRIBUTE_NAME, content);
            }
            isEmptyContent = OrbeonXFormsFilter.isBlank(content);
        } else {
            // Assume content is not blank
            isEmptyContent = false;
        }

        // Provide media type if available  
        if (responseWrapper.getMediaType() != null)
            renderRequest.setAttribute(OrbeonXFormsFilter.RENDERER_CONTENT_TYPE_ATTRIBUTE_NAME, responseWrapper.getMediaType());

        // Forward to Orbeon Forms for rendering only if there is content to be rendered, otherwise just return and
        // let the filterChain finish its life naturally, assuming that when sendRedirect is used, no content is
        // available in the response object
        if (!isEmptyContent) {
            // Forward to the trampoline servlet, which decides whether to forward to a local servlet (integrated
            // deployment) or to a servlet within another webapp (separate deployment)
            setRequestRequestAttributes(renderRequest, renderResponse, "GET", OrbeonXFormsFilter.RENDERER_PATH + "?orbeon-embeddable=true");
            getTrampolineDispatcher(TRAMPOLINE_PATH).forward(renderRequest, renderResponse);
        }
    }

    /**
     * Filter resource requests.
     */
    public void doFilter(ResourceRequest resourceRequest, ResourceResponse resourceResponse, FilterChain filterChain) throws IOException, PortletException {

        final String resourceId = resourceRequest.getResourceID();
        if (resourceId != null && resourceId.matches(applicationPathRegexp)) {
            // This is an Orbeon resource: let Orbeon handle it
            setRequestRequestAttributes(resourceRequest, resourceResponse, resourceRequest.getMethod(), resourceId);
            getTrampolineDispatcher(TRAMPOLINE_PATH).forward(resourceRequest, resourceResponse);
        } else {
            // Not an Orbeon resource: just apply the filter
            filterChain.doFilter(resourceRequest, resourceResponse);
        }
    }

    protected void setRequestRequestAttributes(PortletRequest request, MimeResponse response, String method, String path) {

        // Notify that we are in separate deployment
        request.setAttribute(OrbeonXFormsFilter.RENDERER_DEPLOYMENT_ATTRIBUTE_NAME, "separate");

        // Set rewriting information
        request.setAttribute(PORTLET_NAMESPACE_TEMPLATE_ATTRIBUTE, response.getNamespace());

        {
            final PortletURL renderURL = response.createRenderURL();
            renderURL.setParameter(PATH_PARAMETER_NAME, PATH_TEMPLATE);

            request.setAttribute(PORTLET_RENDER_URL_TEMPLATE_ATTRIBUTE, renderURL.toString());
        }
        {
            final PortletURL actionURL = response.createActionURL();
            actionURL.setParameter(PATH_PARAMETER_NAME, PATH_TEMPLATE);

            request.setAttribute(PORTLET_ACTION_URL_TEMPLATE_ATTRIBUTE, actionURL.toString());
        }
        {
            final ResourceURL resourceURL = response.createResourceURL();
            resourceURL.setResourceID(PATH_TEMPLATE);
            request.setAttribute(PORTLET_RESOURCE_URL_TEMPLATE_ATTRIBUTE, resourceURL.toString());
        }

        // Set request information
        request.setAttribute(PORTLET_METHOD_ATTRIBUTE, method);
        request.setAttribute(PORTLET_PATH_QUERY_ATTRIBUTE, path);
    }

    protected PortletRequestDispatcher getTrampolineDispatcher(String path) throws PortletException {
        final PortletRequestDispatcher dispatcher = portletContext.getRequestDispatcher(path);
        if (dispatcher == null)
            throw new PortletException("Can't find Orbeon Forms trampoline request dispatcher.");

        return dispatcher;
    }

    public void destroy() {
    }

    // NOTE: This is borrowed from NetUtils but we don't want the dependency
    public static String getContentTypeCharset(String contentType) {
        if (contentType == null)
            return null;
        int semicolonIndex = contentType.indexOf(";");
        if (semicolonIndex == -1)
            return null;
        int charsetIndex = contentType.indexOf("charset=", semicolonIndex);
        if (charsetIndex == -1)
            return null;
        // FIXME: There may be other attributes after charset, right?
        String afterCharset = contentType.substring(charsetIndex + 8);
        afterCharset = afterCharset.replace('"', ' ');
        return afterCharset.trim();
    }

    // NOTE: This is borrowed from NetUtils but we don't want the dependency
    public static String getContentTypeMediaType(String contentType) {
        if (contentType == null || contentType.equalsIgnoreCase("content/unknown"))
            return null;
        int semicolonIndex = contentType.indexOf(";");
        if (semicolonIndex == -1)
            return contentType;
        return contentType.substring(0, semicolonIndex).trim();
    }

    protected static class FilterResponseWrapper extends RenderResponseWrapper {

        protected ByteArrayOutputStream byteArrayOutputStream;
        protected OutputStream outputStream;

        protected StringWriter stringWriter;
        protected PrintWriter printWriter;

        protected String encoding;
        protected String mediatype;

        public FilterResponseWrapper(RenderResponse response) {
            super(response);
        }

        public String getCharacterEncoding() {
            // TODO: we don't support setLocale()
            return (encoding == null) ? DEFAULT_ENCODING : encoding;
        }

        public String getMediaType() {
            return mediatype;
        }

        public String getContent() throws IOException {
            if (stringWriter != null) {
                // getWriter() was used
                printWriter.flush();
                return stringWriter.toString();
            } else if (outputStream != null) {
                // getOutputStream() was used
                outputStream.flush();
                return new String(byteArrayOutputStream.toByteArray(), getCharacterEncoding());
            } else {
                return null;
            }
        }

        @Override
        public OutputStream getPortletOutputStream() throws IOException {
            if (byteArrayOutputStream == null) {
                byteArrayOutputStream = new ByteArrayOutputStream();
                outputStream = new OutputStream() {
                    public void write(int i) throws IOException {
                        byteArrayOutputStream.write(i);
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (printWriter == null) {
                stringWriter = new StringWriter();
                printWriter = new PrintWriter(stringWriter);
            }
            return printWriter;
        }

        @Override
        public void setContentType(String contentType) {
            this.encoding = getContentTypeCharset(contentType);
            this.mediatype = getContentTypeMediaType(contentType);
        }
    }
}
