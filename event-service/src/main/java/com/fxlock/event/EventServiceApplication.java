package com.fxlock.event;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.fxlock.event", "com.fxlock.common"})
@EnableScheduling
public class EventServiceApplication 
{
  public static void main(String[] args) 
  {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    SpringApplication.run(EventServiceApplication.class, args);
  }
}



