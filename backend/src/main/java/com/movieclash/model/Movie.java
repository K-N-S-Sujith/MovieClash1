package com.movieclash.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Movie {
    private String id;
    private String title;
    private int year;
    private List<String> aliases;
    private String hint;

    public Movie() {}

    public Movie(String id, String title, int year, List<String> aliases, String hint) {
        this.id = id;
        this.title = title;
        this.year = year;
        this.aliases = aliases;
        this.hint = hint;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }
}

