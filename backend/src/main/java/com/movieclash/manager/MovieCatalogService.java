package com.movieclash.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.model.Movie;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MovieCatalogService {
    private static final Logger log = LoggerFactory.getLogger(MovieCatalogService.class);
    private final ObjectMapper objectMapper;
    private final List<Movie> movies = new ArrayList<>();
    private final Map<String, Movie> movieMap = new HashMap<>();

    public MovieCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadMovies() {
        try {
            ClassPathResource resource = new ClassPathResource("movies.json");
            try (InputStream is = resource.getInputStream()) {
                List<Movie> loaded = objectMapper.readValue(is, new TypeReference<List<Movie>>() {});
                movies.clear();
                movieMap.clear();
                for (Movie m : loaded) {
                    movies.add(m);
                    movieMap.put(m.getId(), m);
                }
                log.info("Successfully loaded {} movies from bundled movies.json", movies.size());
            }
        } catch (Exception e) {
            log.error("Failed to load movies.json", e);
            throw new IllegalStateException("Could not initialize MovieCatalogService", e);
        }
    }

    public Movie getRandomMovie(Set<String> excludedIds) {
        if (movies.isEmpty()) {
            throw new IllegalStateException("Movie catalog is empty");
        }

        List<Movie> candidates = new ArrayList<>();
        for (Movie m : movies) {
            if (excludedIds == null || !excludedIds.contains(m.getId())) {
                candidates.add(m);
            }
        }

        // If all movies were used, fallback to full catalog
        if (candidates.isEmpty()) {
            candidates = movies;
        }

        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(index);
    }

    public Movie getMovieById(String id) {
        return movieMap.get(id);
    }

    public int getTotalMovieCount() {
        return movies.size();
    }
}

