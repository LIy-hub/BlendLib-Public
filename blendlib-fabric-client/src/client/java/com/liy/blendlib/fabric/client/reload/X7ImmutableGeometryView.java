package com.liy.blendlib.fabric.client.reload;

/**
 * Non-owning, immutable adapter contract for one strict-v1 triangle geometry payload.
 *
 * <p>This is intentionally a view rather than a copy or replacement for {@code StaticGeometry}.
 * The current P4 handle does not expose a read-only geometry view, so a future integration owner
 * must provide a narrow bridge at its existing preparation boundary. This type must never become a
 * second model/geometry table or be consulted from submit.</p>
 */
interface X7ImmutableGeometryView {
    int vertexCount();

    int indexCount();

    float positionX(int vertexIndex);

    float positionY(int vertexIndex);

    float positionZ(int vertexIndex);

    float normalX(int vertexIndex);

    float normalY(int vertexIndex);

    float normalZ(int vertexIndex);

    float u(int vertexIndex);

    float v(int vertexIndex);

    int index(int indexOffset);
}
