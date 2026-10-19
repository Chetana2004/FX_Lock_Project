package com.fxlock.rate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.fxlock.rate", "com.fxlock.common"})
@EnableScheduling
public class RateServiceApplication 
{
  public static void main(String[] args) 
  {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    SpringApplication.run(RateServiceApplication.class, args);
  }
}
