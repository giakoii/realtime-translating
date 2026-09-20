package app.realtimetranslator.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs {@link PipelineStage}s in order. Stops early when a stage
 * calls {@link PipelineContext#abort(String)}.
 * Stage ordering is controlled by Spring's {@code @Order} annotation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationPipeline {

    private final List<PipelineStage> stages;

    public void execute(PipelineContext context) {
        for (PipelineStage stage : stages) {
            if (context.isAborted()) {
                log.warn("[Pipeline] Aborted before {}: {}",
                        stage.getClass().getSimpleName(), context.getAbortReason());
                return;
            }

            log.debug("[Pipeline] Running stage: {}", stage.getClass().getSimpleName());
            stage.process(context);
        }

        if (context.isAborted()) {
            log.warn("[Pipeline] Aborted after last stage: {}", context.getAbortReason());
        }
    }
}
