package com.backend.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    @Bean
    Cloudinary cloudinary(CloudinaryProperties properties) {
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", valueOrEmpty(properties.cloudName()),
                "api_key", valueOrEmpty(properties.apiKey()),
                "api_secret", valueOrEmpty(properties.apiSecret()),
                "secure", true));
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
