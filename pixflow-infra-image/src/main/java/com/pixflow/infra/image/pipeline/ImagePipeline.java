package com.pixflow.infra.image.pipeline;

import com.pixflow.infra.image.EncodeSpec;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.infra.image.op.MultiImageOp;
import java.io.InputStream;
import java.util.List;

public interface ImagePipeline {
    byte[] run(InputStream source, List<ImageOp> ops, EncodeSpec encode);

    byte[] runComposed(
            List<InputStream> members,
            List<ImageOp> perMemberOps,
            MultiImageOp compose,
            List<ImageOp> postOps,
            EncodeSpec encode);
}
