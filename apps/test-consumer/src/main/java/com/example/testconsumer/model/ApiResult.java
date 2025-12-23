package com.example.testconsumer.model;

/**
 * Result wrapper containing the API response and HTTP status code.
 */
public record ApiResult(
    AccountResponse response,
    int httpStatusCode
) {
    public static ApiResult success(AccountResponse response) {
        return new ApiResult(response, 200);
    }

    public static ApiResult success(AccountResponse response, int statusCode) {
        return new ApiResult(response, statusCode);
    }
}

