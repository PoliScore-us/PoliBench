package us.poliscore.polibench.models;

import lombok.Data;

@Data
public class Task {
	private String id;
    private String billText;
    private String expected;
}
