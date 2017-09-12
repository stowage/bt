package bt.processor;

import bt.processor.listener.ListenerSource;
import bt.processor.listener.ProcessingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

public class ChainProcessor<C extends ProcessingContext> implements Processor<C> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChainProcessor.class);

    private ProcessingStage<C> chainHead;
    private ExecutorService executor;
    private Optional<ContextFinalizer<C>> finalizer;

    public ChainProcessor(ProcessingStage<C> chainHead,
                          ExecutorService executor) {
        this(chainHead, executor, Optional.empty());
    }

    public ChainProcessor(ProcessingStage<C> chainHead,
                          ExecutorService executor,
                          ContextFinalizer<C> finalizer) {
        this(chainHead, executor, Optional.of(finalizer));
    }

    private ChainProcessor(ProcessingStage<C> chainHead,
                          ExecutorService executor,
                          Optional<ContextFinalizer<C>> finalizer) {
        this.chainHead = chainHead;
        this.finalizer = finalizer;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<?> process(C context, ListenerSource<C> listenerSource) {
        Runnable r = () -> executeStage(chainHead, context, listenerSource);
        return CompletableFuture.runAsync(r, executor);
    }

    private void executeStage(ProcessingStage<C> chainHead,
                              C context,
                              ListenerSource<C> listenerSource) {
        ProcessingEvent stageFinished = chainHead.after();
        Collection<BiFunction<C, ProcessingStage<C>, ProcessingStage<C>>> listeners;
        if (stageFinished != null) {
            listeners = listenerSource.getListeners(stageFinished);
        } else {
            listeners = Collections.emptyList();
        }

        ProcessingStage<C> next = doExecute(chainHead, context, listeners);
        if (next != null) {
            executeStage(next, context, listenerSource);
        }
    }

    private ProcessingStage<C> doExecute(ProcessingStage<C> stage,
                                         C context,
                                         Collection<BiFunction<C, ProcessingStage<C>, ProcessingStage<C>>> listeners) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Processing next stage: torrent ID (%s), stage (%s)",
                    context.getTorrentId().orElse(null), stage.getClass().getName()));
        }

        ProcessingStage<C> next;
        try {
            next = stage.execute(context);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Finished processing stage: torrent ID (%s), stage (%s)",
                        context.getTorrentId().orElse(null), stage.getClass().getName()));
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Processing failed with error: torrent ID (%s), stage (%s)",
                    context.getTorrentId().orElse(null), stage.getClass().getName()), e);
            finalizer.ifPresent(f -> f.finalizeContext(context));
            throw e;
        }

        for (BiFunction<C, ProcessingStage<C>, ProcessingStage<C>> listener : listeners) {
            try {
                // TODO: different listeners may return different next stages (including nulls)
                next = listener.apply(context, next);
            } catch (Exception e) {
                LOGGER.error("Listener invocation failed", e);
            }
        }

        if (next == null) {
            finalizer.ifPresent(f -> f.finalizeContext(context));
        }
        return next;
    }
}
