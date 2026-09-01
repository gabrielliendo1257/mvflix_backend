package com.guille.media.bff.presenter.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.guille.media.bff.experience.addmedia.application.DownstreamRejectionException;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
  @Test
  void downstreamRejectionDoesNotExposeInternalMessage() {
    var response = new ApiExceptionHandler()
        .downstreamRejection(new DownstreamRejectionException(409,
            "http://movies:4040/internal?token=secret"))
        .block();

    assertEquals(409, response.getStatusCode().value());
    assertEquals("DOWNSTREAM_REJECTED", response.getBody().error());
    assertEquals("La solicitud fue rechazada por un servicio dependiente",
        response.getBody().message());
  }
}
