package org.booklore.model.dto.response.ranobedbapi;

import lombok.Data;
import java.util.List;

@Data
public class RanobedbSearchResponse{
  private List<Book> books;
  private String count;
  private int currentPage;
  private int totalPages;

  @Data
  public static class Book {
    private int id;
  }
}
