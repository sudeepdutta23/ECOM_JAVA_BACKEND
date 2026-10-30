package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.security.AccessToken;

/** Everything a successful login produces. The controller splits it into body and cookie. */
public record LoginResult(AccessToken accessToken, IssuedRefreshToken refreshToken) {
}
