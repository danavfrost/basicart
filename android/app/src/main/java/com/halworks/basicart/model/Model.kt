package com.halworks.basicart.model

/**
 * In-memory document model for project format v1 (shared/FORMAT.md).
 *
 * Everything is immutable so undo/redo can keep whole-document snapshots that share
 * unchanged structure. Colors are Android ARGB ints (converted from/to "#RRGGBBAA").
 */

const val FORMAT_VERSION = 1

data class CanvasSpec(val width: Int, val height: Int, val background: Int = WHITE)

data class Transform(
    val x: Double,
    val y: Double,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
)

/** Fields every layer has (FORMAT.md §5). */
data class LayerBase(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Double = 1.0,
    val blendMode: String = "normal",
    val transform: Transform,
    /** Eraser mask: eraser strokes in layer-local px, applied destination-out inside the layer's group. */
    val mask: List<Stroke> = emptyList(),
    /** Transient: how the mask file looked on load (CORRUPT → renamed on next save). */
    val maskState: StrokesState = StrokesState.OK,
)

sealed interface Layer {
    val base: LayerBase
    fun withBase(b: LayerBase): Layer

    /** Box size in local px (FORMAT.md §4). Text boxes are measured, so they come from the layout engine. */
    val typeName: String
}

val Layer.id get() = base.id
val Layer.name get() = base.name
val Layer.visible get() = base.visible
val Layer.locked get() = base.locked
val Layer.opacity get() = base.opacity
val Layer.transform get() = base.transform
fun Layer.withTransform(t: Transform) = withBase(base.copy(transform = t))

// ---------------------------------------------------------------- image

data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double)
data class Adjust(
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val warmth: Double = 0.0,
) {
    val isIdentity get() = brightness == 0.0 && contrast == 0.0 && saturation == 0.0 && warmth == 0.0
}

data class Border(val enabled: Boolean = false, val width: Double = 0.0, val color: Int = WHITE)

data class ImageLayer(
    override val base: LayerBase,
    val assetRef: String,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val crop: CropRect = CropRect(0.0, 0.0, naturalWidth.toDouble(), naturalHeight.toDouble()),
    val rotate90: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val adjust: Adjust = Adjust(),
    val cornerRadius: Double = 0.0,
    val border: Border = Border(),
) : Layer {
    override fun withBase(b: LayerBase) = copy(base = b)
    override val typeName get() = "image"
    val boxWidth: Double get() = if (rotate90 % 2 == 1) crop.height else crop.width
    val boxHeight: Double get() = if (rotate90 % 2 == 1) crop.width else crop.height
}

// ---------------------------------------------------------------- text

enum class TextAlign(val json: String) { LEFT("left"), CENTER("center"), RIGHT("right"), JUSTIFY("justify") }
enum class TextCase(val json: String) { NONE("none"), UPPER("upper"), LOWER("lower"), TITLE("title") }
enum class OutlineStyle(val json: String) { SOLID("solid"), DOUBLE("double"), GLOW("glow") }
enum class Join(val json: String) { ROUND("round"), MITER("miter") }

data class GradientStop(val offset: Double, val color: Int)

sealed interface Fill {
    data class Solid(val color: Int) : Fill
    data class Linear(val angle: Double, val stops: List<GradientStop>) : Fill
    data class Radial(val stops: List<GradientStop>) : Fill
}

data class Outline(
    val enabled: Boolean = false,
    val style: OutlineStyle = OutlineStyle.SOLID,
    val color: Int = BLACK,
    val width: Double = 0.06,
    val color2: Int = WHITE,
    val width2: Double = 0.06,
    val glowRadius: Double = 0.3,
    val join: Join = Join.ROUND,
)

data class Shadow(
    val enabled: Boolean = false,
    val color: Int = 0x80000000.toInt(),
    val blur: Double = 0.1,
    val offsetX: Double = 0.05,
    val offsetY: Double = 0.05,
)

data class BackgroundBox(
    val enabled: Boolean = false,
    val color: Int = 0x99000000.toInt(),
    val padding: Double = 0.25,
    val cornerRadius: Double = 0.15,
)

data class StyleFlags(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
) {
    fun get(f: StyleFlag) = when (f) {
        StyleFlag.BOLD -> bold; StyleFlag.ITALIC -> italic
        StyleFlag.UNDERLINE -> underline; StyleFlag.STRIKE -> strike
    }

    fun with(f: StyleFlag, v: Boolean) = when (f) {
        StyleFlag.BOLD -> copy(bold = v); StyleFlag.ITALIC -> copy(italic = v)
        StyleFlag.UNDERLINE -> copy(underline = v); StyleFlag.STRIKE -> copy(strike = v)
    }
}

enum class StyleFlag { BOLD, ITALIC, UNDERLINE, STRIKE }


data class TextLayer(
    override val base: LayerBase,
    val text: String,
    val fontId: String = "inter",
    val fontSize: Double = 64.0,
    val weight: Int = 400,
    val flags: StyleFlags = StyleFlags(),
    val spans: List<Span> = emptyList(),
    val align: TextAlign = TextAlign.CENTER,
    val letterSpacing: Double = 0.0,
    val lineHeight: Double = 1.2,
    val textCase: TextCase = TextCase.NONE,
    val autoWidth: Boolean = true,
    val boxWidth: Double = 0.0,
    val fill: Fill = Fill.Solid(BLACK),
    val outline: Outline = Outline(),
    val shadow: Shadow = Shadow(),
    val backgroundBox: BackgroundBox = BackgroundBox(),
    val curve: Double = 0.0,
    val skew: Double = 0.0,
) : Layer {
    override fun withBase(b: LayerBase) = copy(base = b)
    override val typeName get() = "text"

    /** Resets every *style* field to its default (preset application, FORMAT.md §12). */
    fun withDefaultStyle() = TextLayer(
        base = base, text = text, fontSize = fontSize, autoWidth = autoWidth,
        boxWidth = boxWidth, spans = spans,
    )
}

// ---------------------------------------------------------------- shape

enum class ShapeKind(val json: String) {
    RECT("rect"), ROUND_RECT("roundRect"), ELLIPSE("ellipse"), LINE("line"), ARROW("arrow");
    val isLinear get() = this == LINE || this == ARROW
}

enum class ArrowHeads(val json: String) { END("end"), START("start"), BOTH("both") }

data class ShapeFill(val enabled: Boolean = true, val color: Int = 0xFF3478F6.toInt())
data class ShapeStroke(
    val enabled: Boolean = false,
    val color: Int = BLACK,
    val width: Double = 8.0,
    val join: Join = Join.MITER,
)

data class ShapeLayer(
    override val base: LayerBase,
    val shape: ShapeKind,
    val width: Double,
    val height: Double,
    val fill: ShapeFill = ShapeFill(),
    val stroke: ShapeStroke = ShapeStroke(),
    val cornerRadius: Double = 24.0,
    val arrowHeads: ArrowHeads = ArrowHeads.END,
) : Layer {
    override fun withBase(b: LayerBase) = copy(base = b)
    override val typeName get() = "shape"
}

// ---------------------------------------------------------------- drawing

enum class Brush(val json: String) {
    PEN("pen"), MARKER("marker"), HIGHLIGHTER("highlighter"), AIRBRUSH("airbrush"),
    CALLIGRAPHY("calligraphy"), PENCIL("pencil"), ERASER("eraser");

    companion object {
        fun fromJson(s: String) = entries.firstOrNull { it.json == s }
    }
}

/** Immutable float array with structural equality (identity fast path). */
class FloatList(private val data: FloatArray) {
    val size get() = data.size
    operator fun get(i: Int) = data[i]
    fun toArray(): FloatArray = data.copyOf()
    override fun equals(other: Any?) = this === other || (other is FloatList && data.contentEquals(other.data))
    override fun hashCode() = data.contentHashCode()
    override fun toString() = data.contentToString()
}

data class Stroke(
    val brush: Brush,
    val size: Double = 12.0,
    val color: Int = BLACK,
    val opacity: Double = 1.0,
    /** Flat x,y pairs in local px. */
    val points: FloatList,
    val pressure: FloatList? = null,
) {
    val pointCount get() = points.size / 2
}

enum class StrokesState { OK, MISSING, CORRUPT }

data class DrawingLayer(
    override val base: LayerBase,
    val width: Int,
    val height: Int,
    val strokes: List<Stroke> = emptyList(),
    /** Transient: how the strokes file looked on load (not serialized into project.json). */
    val strokesState: StrokesState = StrokesState.OK,
) : Layer {
    override fun withBase(b: LayerBase) = copy(base = b)
    override val typeName get() = "drawing"
}

// ---------------------------------------------------------------- document

data class Document(
    val id: String,
    val name: String,
    val created: String,
    val modified: String,
    val generator: String,
    val canvas: CanvasSpec,
    val layers: List<Layer>,
) {
    fun layer(id: String) = layers.firstOrNull { it.id == id }
    fun indexOf(id: String) = layers.indexOfFirst { it.id == id }

    fun replaceLayer(updated: Layer): Document =
        copy(layers = layers.map { if (it.id == updated.id) updated else it })
}

const val WHITE = 0xFFFFFFFF.toInt()
const val BLACK = 0xFF000000.toInt()
const val TRANSPARENT = 0

/** Non-text layer box size in local px; null for text (measured by the layout engine). */
fun Layer.fixedBox(): Pair<Double, Double>? = when (this) {
    is ImageLayer -> boxWidth to boxHeight
    is ShapeLayer -> width to height
    is DrawingLayer -> width.toDouble() to height.toDouble()
    is TextLayer -> null
}
