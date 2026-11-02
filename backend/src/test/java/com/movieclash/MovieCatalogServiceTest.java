package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.manager.MovieCatalogService;
import com.movieclash.model.Movie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MovieCatalogServiceTest {

    private MovieCatalogService movieCatalogService;

    @BeforeEach
    void setUp() {
        movieCatalogService = new MovieCatalogService(new ObjectMapper());
        movieCatalogService.loadMovies();
    }

    @Test
    void testMovieCatalogLoadsAtLeast100Movies() {
        assertTrue(movieCatalogService.getTotalMovieCount() >= 100,
                "Expected at least 100 movies loaded, found " + movieCatalogService.getTotalMovieCount());
    }

    @Test
    void testGetRandomMovieReturnsValidMovie() {
        Movie movie = movieCatalogService.getRandomMovie(null);
        assertNotNull(movie);
        assertNotNull(movie.getId());
        assertNotNull(movie.getTitle());
        assertTrue(movie.getYear() > 1900);
        assertNotNull(movie.getAliases());
    }

    @Test
    void testGetRandomMovieRespectsExclusions() {
        Set<String> exclusions = new HashSet<>();
        // Exclude m-001
        exclusions.add("m-001");
        for (int i = 0; i < 20; i++) {
            Movie movie = movieCatalogService.getRandomMovie(exclusions);
            assertNotEquals("m-001", movie.getId(), "Excluded movie ID should not be selected");
        }
    }
}

