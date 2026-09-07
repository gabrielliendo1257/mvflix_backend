package com.gcorp.service.app.mvflix_playback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MvflixPlaybackApplication {
  public static void main(String[] args) {
    SpringApplication.run(MvflixPlaybackApplication.class, args);
  }
}
