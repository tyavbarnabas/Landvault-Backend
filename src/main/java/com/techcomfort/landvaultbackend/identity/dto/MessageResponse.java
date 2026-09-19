package com.techcomfort.landvaultbackend.identity.dto;

/**
 * A plain confirmation for endpoints that deliberately have nothing to
 * return — notably {@code forgot-password}, whose whole point is that the
 * response reveals nothing about whether the account exists.
 */
public record MessageResponse(String message) {
}
