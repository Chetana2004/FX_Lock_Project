package com.fxlock.common;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException 
{
  public final HttpStatus status;

  public ApiError(HttpStatus s, String message) 
  {
    super(message);
    status = s;
  }

  public static ApiError bad(String m) 
  {
    return new ApiError(HttpStatus.BAD_REQUEST, m);
  }

  public static ApiError conflict(String m) 
  {
    return new ApiError(HttpStatus.CONFLICT, m);
  }

  public static ApiError unavailable(String m) 
  {
    return new ApiError(HttpStatus.SERVICE_UNAVAILABLE, m);
  }

  public static ApiError missing() 
  {
    return new ApiError(HttpStatus.NOT_FOUND, "Record not found");
  }
  
}
