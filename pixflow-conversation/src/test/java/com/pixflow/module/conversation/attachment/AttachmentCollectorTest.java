package com.pixflow.module.conversation.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.module.file.pkg.ImageReference;
import com.pixflow.module.file.pkg.PackageReference;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttachmentCollectorTest {
    private final AttachmentCollector collector = new AttachmentCollector(new FakeResolver());
    private final AttachmentMapper mapper = new AttachmentMapper();

    @Test
    void collectsUploadAndPackageImages() {
        List<Attachment> attachments = collector.collect(
                new UserPrompt("处理图片", List.of(new UserAttachmentInput(
                        "upload-1",
                        AttachmentType.UPLOAD_IMAGE,
                        "object://uploads/a.png",
                        null,
                        Map.of("mime", "image/png")))),
                new PackageBinding("7"));

        assertThat(attachments).hasSize(3);
        assertThat(attachments).extracting(Attachment::type)
                .containsExactly(AttachmentType.UPLOAD_IMAGE, AttachmentType.PACKAGE_REFERENCE, AttachmentType.PACKAGE_REFERENCE);
    }

    @Test
    void mapsAttachmentsToContextAttachmentMessages() {
        Attachment attachment = new Attachment(
                "upload-1",
                AttachmentType.UPLOAD_IMAGE,
                "object://uploads/a.png",
                "7",
                Map.of("mime", "image/png"));

        Message message = mapper.toContextMessage(attachment);

        assertThat(message.role()).isEqualTo(MessageRole.ATTACHMENT);
        assertThat(message.metadata().values())
                .containsEntry(MessageMetadata.ATTACHMENT_TYPE, "UPLOAD_IMAGE")
                .containsEntry(MessageMetadata.ATTACHMENT_REF, "object://uploads/a.png")
                .containsEntry(MessageMetadata.ATTACHED_PACKAGE_ID, "7");
    }

    private static final class FakeResolver implements PackageReferenceResolver {
        @Override
        public PackageReference resolve(String packageId) {
            return new PackageReference(packageId, "pkg", packageId + "/", listImages(packageId));
        }

        @Override
        public List<ImageReference> listImages(String packageId) {
            return List.of(
                    new ImageReference("1", "packages/7/1.png", "1.png", "sku-1", "g1", "front"),
                    new ImageReference("2", "packages/7/2.png", "2.png", "sku-2", "g2", "side"));
        }
    }
}
