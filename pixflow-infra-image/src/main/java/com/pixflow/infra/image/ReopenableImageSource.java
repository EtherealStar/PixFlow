package com.pixflow.infra.image;

import java.io.InputStream;

@FunctionalInterface
public interface ReopenableImageSource {
    InputStream openStream();
}
