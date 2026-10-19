package com.fxlock.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(
		
    classes = AccessControlTest.TestApp.class,
    properties = 
	{
      "fx.auth.alice=alice-test",
      "fx.auth.bob=bob-test",
      "fx.auth.ops=ops-test",
      "fx.auth.service=svc-test",
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false"
    })

@AutoConfigureMockMvc
class AccessControlTest 
{
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  @Import({SecurityConfig.class, Endpoints.class})
  static class TestApp
  {
	  
  }

  @RestController
  static class Endpoints 
  {
    @GetMapping("/api/identity")
    String identity(Principal p) 
    {
      return p.getName();
    }

    @GetMapping({"/ops/status", "/internal/status"})
    String check() 
    {
      return "ok";
    }
  }

  @Autowired MockMvc mvc;
  String auth(String user, String pass) 
  {
    return "Basic "
        + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void anonymousAndWrongPasswordReject() throws Exception 
  {
    mvc.perform(get("/api/identity")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/identity").header("Authorization", auth("alice", "wrong")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedOwner() throws Exception 
  {
    mvc.perform(get("/api/identity").header("Authorization", auth("alice", "alice-test")))
        .andExpect(status().isOk())
        .andExpect(content().string("alice"));
  }

  @Test
  void customerCannotReadOperations() throws Exception 
  {
    for (String path : new String[] {"/ops/status", "/internal/status"})
      mvc.perform(get(path).header("Authorization", auth("alice", "alice-test")))
          .andExpect(status().isForbidden());
  }

  @Test
  void rolesAreSeparate() throws Exception 
  {
    mvc.perform(get("/ops/status").header("Authorization", auth("ops", "ops-test")))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/status").header("Authorization", auth("svc", "svc-test")))
        .andExpect(status().isOk());
    mvc.perform(get("/ops/status").header("Authorization", auth("svc", "svc-test")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/identity").header("Authorization", auth("svc", "svc-test")))
        .andExpect(status().isForbidden());
  }
}

