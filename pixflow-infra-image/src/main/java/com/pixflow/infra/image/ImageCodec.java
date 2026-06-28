package com.pixflow.infra.image;

import java.io.InputStream;

public interface ImageCodec {
    /**
     * Reads image metadata from the supplied stream. Implementations may consume the
     * stream but must not close it; callers that need to decode after probing should
     * provide a new stream over the same bytes.
     */
    ImageProbe probe(InputStream data);

    /**
     * Decodes the supplied stream into an immutable raster handle. Implementations may
     * consume the stream but must not close it.
     */
    RasterImage decode(InputStream data);

    byte[] encode(RasterImage image, EncodeSpec spec);
}
