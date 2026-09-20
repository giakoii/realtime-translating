package app.realtimetranslator.pipeline;

/**
 * A single step in the translation pipeline.
 * Stages are executed in {@link org.springframework.core.annotation.Order} sequence
 * by {@link TranslationPipeline}.
 */
public interface PipelineStage {

    void process(PipelineContext context);
}
