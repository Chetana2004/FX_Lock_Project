package com.fxlock.discoveryserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication(scanBasePackages = {"com.fxlock.discoveryserver", "com.fxlock.common"})
@EnableEurekaServer
public class DiscoveryServerApplication 
{
  public static void main(String[] args) 
  {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    SpringApplication.run(DiscoveryServerApplication.class, args);
  }
}



