package com.fxlock.common;

import java.time.Clock;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SharedConfig 
{
  @Bean
  Clock clock() 
  {
    return Clock.systemUTC();
  }

  @Bean
  @LoadBalanced
  RestTemplate services() 
  {
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(3000);
    f.setReadTimeout(10000);
    return new RestTemplate(f);
  }
}


