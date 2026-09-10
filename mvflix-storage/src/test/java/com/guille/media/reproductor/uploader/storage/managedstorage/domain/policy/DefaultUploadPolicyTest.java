package com.guille.media.reproductor.uploader.storage.managedstorage.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MimeType;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.UploadConfiguration.Strategy;
import org.junit.jupiter.api.Test;

class DefaultUploadPolicyTest {
  private final DefaultUploadPolicy policy = new DefaultUploadPolicy();
  private final MimeType video = MimeType.of("video/mp4");

  @Test
  void usesSimplePutAtOrBelow256MiB() {
    assertThat(policy.resolve(256L * 1024 * 1024, video).strategy()).isEqualTo(Strategy.SIMPLE);
  }

  @Test
  void usesMultipartAbove256MiB() {
    assertThat(policy.resolve(256L * 1024 * 1024 + 1, video).strategy()).isEqualTo(Strategy.MULTIPART);
  }
}
