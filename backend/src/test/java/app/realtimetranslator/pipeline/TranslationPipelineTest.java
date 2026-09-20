package app.realtimetranslator.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranslationPipelineTest {

    @Test
    void execute_runsStagesInOrder() {
        List<String> executionOrder = new ArrayList<>();

        PipelineStage stageA = ctx -> executionOrder.add("A");
        PipelineStage stageB = ctx -> executionOrder.add("B");
        PipelineStage stageC = ctx -> executionOrder.add("C");

        var pipeline = new TranslationPipeline(List.of(stageA, stageB, stageC));
        pipeline.execute(new PipelineContext());

        assertEquals(List.of("A", "B", "C"), executionOrder);
    }

    @Test
    void execute_stopsOnAbort() {
        List<String> executionOrder = new ArrayList<>();

        PipelineStage stageA = ctx -> executionOrder.add("A");
        PipelineStage stageB = ctx -> {
            executionOrder.add("B");
            ctx.abort("test abort");
        };
        PipelineStage stageC = ctx -> executionOrder.add("C");

        var pipeline = new TranslationPipeline(List.of(stageA, stageB, stageC));
        var context = new PipelineContext();
        pipeline.execute(context);

        assertEquals(List.of("A", "B"), executionOrder);
        assertTrue(context.isAborted());
        assertEquals("test abort", context.getAbortReason());
    }

    @Test
    void execute_alreadyAbortedContext_runsNoStages() {
        List<String> executionOrder = new ArrayList<>();
        PipelineStage stage = ctx -> executionOrder.add("X");

        var pipeline = new TranslationPipeline(List.of(stage));
        var context = new PipelineContext();
        context.abort("pre-aborted");
        pipeline.execute(context);

        assertTrue(executionOrder.isEmpty());
    }

    @Test
    void execute_emptyStageList_completesNormally() {
        var pipeline = new TranslationPipeline(List.of());
        var context = new PipelineContext();
        pipeline.execute(context);

        assertFalse(context.isAborted());
    }
}
