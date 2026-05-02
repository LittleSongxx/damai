package org.javaup.ai.assistant.skill.general;

public interface WebSearchClient {

    String provider();

    boolean available();

    WebSearchResult search(WebSearchRequest request);
}
