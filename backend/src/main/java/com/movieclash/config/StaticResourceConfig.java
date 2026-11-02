package com.movieclash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Resolve frontend folder whether executed from project root or backend subfolder
        File frontendDir = new File("frontend");
        if (!frontendDir.exists()) {
            frontendDir = new File("../frontend");
        }
        if (!frontendDir.exists()) {
            frontendDir = new File(System.getProperty("user.dir"), "frontend");
        }

        String absPath = frontendDir.getAbsolutePath().replace("\\", "/");
        if (!absPath.endsWith("/")) {
            absPath += "/";
        }
        String location = "file:" + absPath;

        registry.addResourceHandler("/**")
                .addResourceLocations(location, "classpath:/static/")
                .setCachePeriod(0); // Disable caching for rapid local updates
    }
}
