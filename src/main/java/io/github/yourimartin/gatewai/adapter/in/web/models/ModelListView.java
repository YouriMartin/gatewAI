package io.github.yourimartin.gatewai.adapter.in.web.models;

import java.util.List;

/** The body of {@code GET /v1/models}: {@code {"object": "list", "data": [...]}}. */
public record ModelListView(String object, List<ModelView> data) {

  static ModelListView of(List<ModelView> data) {
    return new ModelListView("list", List.copyOf(data));
  }
}
