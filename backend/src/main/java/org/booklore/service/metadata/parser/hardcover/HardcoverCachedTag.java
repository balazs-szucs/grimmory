package org.booklore.service.metadata.parser.hardcover;

import com.dslplatform.json.JsonAttribute;
import lombok.*;
import com.dslplatform.json.CompiledJson;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @CompiledJson
  public class HardcoverCachedTag {
      private String tag;
      
      @JsonAttribute(name = "tagSlug")
      private String tagSlug;
      
      private String category;
      
      @JsonAttribute(name = "categorySlug")
      private String categorySlug;
      
      @JsonAttribute(name = "spoilerRatio")
      private Double spoilerRatio;
      
      private Integer count;
  }

