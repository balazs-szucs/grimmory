package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.enums.TaskType;
import org.booklore.task.options.LibraryRescanOptions;
import org.booklore.util.json.ObjectMapper;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCreateRequest {
    private String taskId;
    private TaskType taskType;
    @Builder.Default
    private boolean triggeredByCron = false;

    private Object options;

    public <T> T getOptionsAs(Class<T> optionsClass) {
        if (options == null) {
            return null;
        }
        if (optionsClass.isInstance(options)) {
            return optionsClass.cast(options);
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(options, optionsClass);
    }
}
