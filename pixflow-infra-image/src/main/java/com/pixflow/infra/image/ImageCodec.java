package com.pixflow.infra.image;

import java.io.InputStream;

public interface ImageCodec {
    ImageProbe probe(InputStream data);

    RasterImage decode(InputStream data);

    byte[] encode(RasterImage image, EncodeSpec spec);
}
