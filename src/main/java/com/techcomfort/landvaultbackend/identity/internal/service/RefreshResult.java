package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.dto.RefreshResponse;

/** A refresh: the body plus the new raw refresh token for the cookie. Not a wire shape. */
public record RefreshResult(RefreshResponse response, String refreshToken) {
}
