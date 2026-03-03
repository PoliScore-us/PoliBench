package us.poliscore.polibench.models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
public class Task {
	private String id;
    private String billText;
    private String expected;
    @JsonIgnore
    private Pillar pillar;
}
