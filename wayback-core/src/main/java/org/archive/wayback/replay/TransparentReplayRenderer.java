/*
 *  This file is part of the Wayback archival access software
 *   (http://archive-access.sourceforge.net/projects/wayback/).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.wayback.replay;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.SpecificCaptureReplayException;

/**
 * ReplayRenderer implementation which returns the archive document as 
 * pristinely as possible -- no modifications to response code, HTTP headers,
 * or original byte-stream.
 *
 * <p>
 * Now this class has a limited support for range requests. If the request
 * has {@code Range} header, it tries to fulfill it by extracting requested
 * byte range from the archived content (either 200 or 206 responses).
 * If the Resource is not suitable for replaying the request ranges,
 * {@code renderResource} throws {@link RangeNotSatisfiable}, so that
 * replay process can try another capture, or take other actions.
 * </p>
 *
 * @author brad
 * @version $Date$, $Revision$
 */
public class TransparentReplayRenderer implements ReplayRenderer {
	private HttpHeaderProcessor httpHeaderProcessor;
	
	// TODO: Figure out best way to generalize this, but probably good default
	// Add special don't cache header in case of at least 100M
	private final static long NOCACHE_THRESHOLD = 100000000L;

	private final static String NOCACHE_HEADER_NAME = "X-Accel-Buffering";
	private final static String NOCACHE_HEADER_VALUE = "no";
	
	private final static int BUFFER_SIZE = 4096;
	public TransparentReplayRenderer(HttpHeaderProcessor httpHeaderProcessor) {
		this.httpHeaderProcessor = httpHeaderProcessor;
	}

	@Override
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource resource,
			ResultURIConverter uriConverter, CaptureSearchResults results)
			throws ServletException, IOException,
			SpecificCaptureReplayException {
		String rangeHeader = httpRequest.getHeader(HttpHeaderOperation.HTTP_RANGE_HEADER_UP);
		if (rangeHeader != null) {
			long[][] ranges = HttpHeaderOperation.parseRanges(rangeHeader);
			@SuppressWarnings("resource")
			RangeResource rangeResource = new RangeResource(resource,
				ranges);
			rangeResource.parseRange();
			resource = rangeResource;
		}

		HttpHeaderOperation.copyHTTPMessageHeader(resource, httpResponse);

		// Note: httpHeaderProcessor may remove Content-Length.
		Map<String, String> headers = HttpHeaderOperation.processHeaders(
			resource, result, uriConverter, httpHeaderProcessor);

		String origLength = HttpHeaderOperation.getContentLength(resource.getHttpHeaders());
		if (origLength != null) {
			HttpHeaderOperation.replaceHeader(headers, HttpHeaderOperation.HTTP_LENGTH_HEADER, origLength);

			long contentLength = -1;
			
			try {
			    contentLength = Long.parseLong(origLength);
			} catch (NumberFormatException n) {
			    
			}
			
			//TODO: Generalize? Don't buffer NOCACHE_THRESHOLD
			if ((contentLength >= NOCACHE_THRESHOLD)) {
			    headers.put(NOCACHE_HEADER_NAME, NOCACHE_HEADER_VALUE);
			}
		}

		HttpHeaderOperation.sendHeaders(headers, httpResponse);

		// and copy the raw byte-stream.
		OutputStream os = httpResponse.getOutputStream();
		byte[] buffer = new byte[BUFFER_SIZE];
		long total = 0;
		for (int r = -1; (r = resource.read(buffer, 0, BUFFER_SIZE)) != -1;) {
			os.write(buffer, 0, r);
			total += r;
		}
		if (total == 0) {
			if (headers.size() == 0) {
				// totally empty response
				httpResponse.setContentLength(0);
			}
		}
	}

	@Override
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource httpHeadersResource,
			Resource payloadResource, ResultURIConverter uriConverter,
			CaptureSearchResults results) throws ServletException, IOException,
			SpecificCaptureReplayException {
		if (httpHeadersResource != payloadResource) {
			httpHeadersResource = new CompositeResource(
				httpHeadersResource, payloadResource);
		}
		renderResource(httpRequest, httpResponse, wbRequest, result,
			httpHeadersResource, uriConverter, results);
	}
}
