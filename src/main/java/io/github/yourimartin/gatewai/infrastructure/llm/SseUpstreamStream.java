package io.github.yourimartin.gatewai.infrastructure.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.llm.ClientDisconnectedException;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.port.out.UpstreamEventStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an OpenAI-format Server-Sent Events stream and relays it event by event
 * (v4 B.4). Each event's {@code data} goes to the client as soon as its blank line
 * is read: nothing is buffered beyond one event. Comments, {@code event:},
 * {@code id:} and {@code retry:} fields are not relayed — OpenAI-format servers
 * put everything in {@code data}.
 *
 * <p>The usage-only chunk that {@code include_usage} adds at the end is read for
 * the accounting, and kept from the client unless the client asked for it itself.
 */
final class SseUpstreamStream implements UpstreamEventStream {

  private static final Logger LOG = LoggerFactory.getLogger(SseUpstreamStream.class);

  private final InputStream body;
  private final boolean clientWantsUsage;
  private final String modelId;
  private volatile boolean cancelled;

  SseUpstreamStream(InputStream body, boolean clientWantsUsage, String modelId) {
    this.body = body;
    this.clientWantsUsage = clientWantsUsage;
    this.modelId = modelId;
  }

  @Override
  public TokenUsage relay(Consumer<String> onEvent) {
    TokenUsage usage = null;
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(body, StandardCharsets.UTF_8))) {
      StringBuilder data = null;
      String line;
      while ((line = reader.readLine()) != null) {
        if (cancelled) {
          throw disconnected(null);
        }
        if (line.isEmpty()) {
          usage = dispatch(data, onEvent, usage);
          data = null;
        } else if (line.startsWith("data:")) {
          String value = line.substring(line.startsWith("data: ") ? 6 : 5);
          data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
        }
      }
      if (cancelled) {
        throw disconnected(null);
      }
      // A server that closes without a final blank line still sent its last event.
      return dispatch(data, onEvent, usage);
    } catch (IOException e) {
      if (cancelled) {
        throw disconnected(e);
      }
      LOG.warn("Pass-through stream of model '{}' failed mid-stream: {}", modelId, e.toString());
      throw new UpstreamFailureException(UpstreamFailureException.Kind.UNAVAILABLE, 0,
          "Upstream of model '" + modelId + "' failed mid-stream");
    }
  }

  private TokenUsage dispatch(StringBuilder data, Consumer<String> onEvent, TokenUsage usage) {
    if (data == null) {
      return usage;
    }
    String event = data.toString();
    PassThroughJson.ChunkUsage chunk = PassThroughJson.chunkUsage(event);
    if (!chunk.usageOnly() || clientWantsUsage) {
      onEvent.accept(event);
    }
    return chunk.usage() == null ? usage : chunk.usage();
  }

  @Override
  public void cancel() {
    cancelled = true;
    try {
      body.close();
    } catch (IOException e) {
      LOG.debug("Closing a cancelled pass-through stream: {}", e.toString());
    }
  }

  private ClientDisconnectedException disconnected(IOException cause) {
    return new ClientDisconnectedException(
        "Stream of model '" + modelId + "' cancelled: the client went away", cause);
  }
}
