package com.pixflow.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.file.api.publication.GeneratedImageKind;
import com.pixflow.module.file.api.publication.GeneratedImagePublisher;
import com.pixflow.module.file.api.publication.PublishGeneratedImage;
import com.pixflow.module.file.api.publication.PublishedImage;
import com.pixflow.module.task.api.publication.CandidateKind;
import com.pixflow.module.task.api.publication.GeneratedAssetCandidate;
import com.pixflow.module.task.api.publication.ProducerIdentity;
import com.pixflow.module.task.api.publication.SourceImageIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskGeneratedAssetPublicationAdapterTest {
  @Test
  void translatesTheCompleteTaskCandidateWithoutLosingLineageOrProvenance() {
    GeneratedImagePublisher publisher = mock(GeneratedImagePublisher.class);
    ObjectLocation candidate = ObjectLocation.of(BucketType.TMP, "generated/9/output.png");
    ObjectLocation stable = ObjectLocation.of(BucketType.GENERATED, "7/images/31.png");
    when(publisher.publish(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new PublishedImage(31L, "package:7/image:31", stable));
    var adapter = new TaskGeneratedAssetPublicationAdapter(publisher);

    var published =
        adapter.publish(
            new GeneratedAssetCandidate(
                9L,
                21L,
                "GENERATIVE:11:branch-a",
                4L,
                7L,
                candidate,
                128L,
                "image/png",
                "png",
                CandidateKind.GENERATIVE,
                List.of(new SourceImageIdentity("11"), new SourceImageIdentity("12")),
                ProducerIdentity.generative("openai", "gpt-image-1")));

    ArgumentCaptor<PublishGeneratedImage> command =
        ArgumentCaptor.forClass(PublishGeneratedImage.class);
    verify(publisher).publish(command.capture());
    assertThat(command.getValue().kind()).isEqualTo(GeneratedImageKind.GENERATIVE);
    assertThat(command.getValue().candidate()).isEqualTo(candidate);
    assertThat(command.getValue().sourceImages())
        .extracting("imageId")
        .containsExactly("11", "12");
    assertThat(command.getValue().producer().provider()).isEqualTo("openai");
    assertThat(command.getValue().producer().model()).isEqualTo("gpt-image-1");
    assertThat(published.imageId()).isEqualTo(31L);
    assertThat(published.referenceKey()).isEqualTo("package:7/image:31");
  }
}
