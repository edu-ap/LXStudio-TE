package titanicsend.pattern.glengine;

import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.GLBuffers;
import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXLoopTask;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.model.LXModel;
import titanicsend.audio.AudioStems;
import titanicsend.pattern.yoffa.shader_engine.ShaderUtils;
import titanicsend.util.TE;
import titanicsend.util.TEMath;

import java.nio.FloatBuffer;

import static com.jogamp.opengl.GL.*;

public class GLEngine extends LXComponent implements LXLoopTask, LX.Listener {
  public static final String PATH = "GLEngine";
  public static GLEngine current;

  private final int[] audioTextureHandle = new int[1];
  private final int[] uniformBlockHandles = new int[2];

  // rendering canvas size.  May be changed
  // via the startup command line.
  private static int xSize;
  private static int ySize;
  private static int maxPoints;

  // Dimensions of mapped texture backbuffer.
  //
  // This buffer is never directly rendered by either the GPU or by Java
  // Instead, the pixel locations corresponding to the lx model's LEDs are
  // colored at frame time and the buffer is supplied as a read-only sampler
  // to the shader system.
  //
  // So increasing the size of this buffer affects memory usage but does
  // not affect rendering performance.  The default size is sufficient for
  // even very large models, but can be increased if necessary.
  // TODO - make this configurable per pattern or effect.
  private static final int mappedBufferWidth = 640;
  private static final int mappedBufferHeight = 640;

  // audio texture size and buffer
  private static final int audioTextureWidth = 512;
  private static final int audioTextureHeight = 2;
  private FloatBuffer audioTextureData;

  // audio data sources & parameters
  private final double AUDIO_LEVEL_MIN = 0.01;
  private final GraphicMeter meter;
  private final float fftResampleFactor;

  private static final TEMath.EMA avgVolume = new TEMath.EMA(0.5, .01);
  private static final TEMath.EMA avgBass = new TEMath.EMA(0.2, .01);
  private static final TEMath.EMA avgTreble = new TEMath.EMA(0.2, .01);

  private static double beat = 0.0;
  private static double sinPhaseBeat = 0.0;
  private static double volume = 0.0;
  private static double bassLevel = 0.0;
  private static double trebleLevel = 0.0;
  private static double volumeRatio = 0.0;
  private static double bassRatio = 0.0;
  private static double trebleRatio = 0.0;

  // audio and related uniform block buffer parameters
  private FloatBuffer perRunUniformBlock;
  private int perRunUniformBlockSize;
  private FloatBuffer perFrameUniformBlock;
  private int perFrameUniformBlockSize;

  public static final int perRunUniformBlockBinding = 0;
  public static final int perFrameUniformBlockBinding = 1;

  // Texture cache management
  private TextureManager textureCache = null;
  private boolean modelChanged = false;

  // Data and utility methods for the GL canvas/context.
  private GLAutoDrawable canvas = null;
  private GL4 gl4;

  private LXModel model;

  public LXModel getModel() {
    return model;
  }

  // Needed for housekeeping, during static-to-dynamic model transition
  // This lets various shader components know if they're running the static model
  // and need to swap the x and z axes.
  // TODO - remove when we move to dynamic model
  private final boolean isStatic;

  public boolean isStaticModel() {
    return isStatic;
  }

  public GLAutoDrawable getCanvas() {
    return canvas;
  }

  public static int getWidth() {
    return xSize;
  }

  public static int getHeight() {
    return ySize;
  }

  public static int getMappedBufferWidth() {
    return mappedBufferWidth;
  }

  public static int getMappedBufferHeight() {
    return mappedBufferHeight;
  }

  // Utility methods to give java patterns access to the audio texture
  // should they want it.
  public FloatBuffer getAudioTextureBuffer() {
    return audioTextureData;
  }

  public static int getAudioTextureWidth() {
    return audioTextureWidth;
  }

  public static int getAudioTextureHeight() {
    return audioTextureHeight;
  }

  /**
   * Copy a model's normalized coordinates into a special texture for use by shaders. Must be
   * called by the parent pattern or effect at least once before the first frame is rendered and
   * Should be called by the pattern's frametime run() function on every frame for full Chromatik view
   * support.
   *
   * @param model The model (view) to copy coordinates from
   * @return The texture unit number that the view's coordinate texture
   * is bound to.
   */
  public int useViewCoordinates(LXModel model) {
    return textureCache.useViewCoordinates(model);
  }

  // Load a static texture from a file and bind it to the next available texture unit
  // Returns the texture unit number. If the texture is already loaded, just increment
  // the ref count and return the existing texture unit number.
  public int useTexture(GL4 gl4, String textureName) {
    return textureCache.useTexture(gl4, textureName);
  }

  public void releaseTexture(String textureName) {
    textureCache.releaseTexture(textureName);
  }

  // Returns the next available texture unit number, either by reusing a released
  // texture unit number or by allocating a new one.
  public int getNextTextureUnit() {
    return textureCache.getNextTextureUnit();
  }

  public int releaseTextureUnit(int textureUnit) {
    return textureCache.releaseTextureUnit(textureUnit);
  }

  /**
   * Retrieve a single sample of the current frame's fft data from the engine NOTE: 512 samples can
   * always be retrieved, regardless of how many bands the engine actually supplies. Data will be
   * properly distributed (but not smoothed or interpolated) across the full 512 sample range.
   *
   * @param index (0-511) of the sample to retrieve.
   * @return fft sample, normalized to range 0 to 1.
   */
  private float getFrequencyData(int index) {
    return meter.getBandf((int) Math.floor((float) index * fftResampleFactor));
  }

  /**
   * Retrieve a single sample of the current frame's waveform data from the engine
   *
   * @param index (0-511) of the sample to retrieve
   * @return waveform sample, range -1 to 1
   */
  private float getWaveformData(int index) {
    return meter.getSamples()[index];
  }

  /**
   * Construct texture to hold audio fft and waveform data and bind it to texture unit 0 for the
   * entire run. Once done, every shader pattern can access the audio data texture with minimal
   * per-pattern work.
   */
  private void initializeAudioTexture() {
    // allocate backing buffer in native memory
    this.audioTextureData = GLBuffers.newDirectFloatBuffer(audioTextureHeight * audioTextureWidth);

    // create texture and bind it to texture unit 0, where it will stay for the whole run
    gl4.glActiveTexture(GL_TEXTURE0);
    gl4.glEnable(GL_TEXTURE_2D);
    gl4.glGenTextures(1, audioTextureHandle, 0);
    gl4.glBindTexture(GL4.GL_TEXTURE_2D, audioTextureHandle[0]);

    // configure texture coordinate handling
    // TODO - would GL_LINEAR filtering look more interesting here?
    gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  }

  /**
   * Update audio texture object with new fft and waveform data. This is called once per frame.
   */
  private void updateAudioTexture() {
    // load frequency and waveform data into our texture buffer, fft data
    // in the first row, normalized audio waveform data in the second.
    for (int n = 0; n < audioTextureWidth; n++) {
      audioTextureData.put(n, getFrequencyData(n));
      audioTextureData.put(n + audioTextureWidth, getWaveformData(n));
    }

    // update audio texture on the GPU from our buffer
    gl4.glActiveTexture(GL_TEXTURE0);
    gl4.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);

    gl4.glTexImage2D(
      GL4.GL_TEXTURE_2D,
      0,
      GL4.GL_R32F,
      audioTextureWidth,
      audioTextureHeight,
      0,
      GL4.GL_RED,
      GL_FLOAT,
      audioTextureData);
  }

  /**
   * Return a properly aligned size for a uniform block. This is necessary, particularly on
   * macOS, where the size of a uniform block must be a multiple of GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT.
   * (NVidia drivers on Windows and Linux are more forgiving.  I always discover things like this the
   * hard way!)
   *
   * @param elements The number of 4-byte elements in the uniform block
   * @return The size of the uniform block in bytes, aligned to the GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT
   */
  private int getUBOAlignedSize(int elements) {
    int[] alignment = new int[1];
    gl4.glGetIntegerv(GL4.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, alignment, 0);
    return ((elements * 4) + alignment[0] - 1) & ~(alignment[0] - 1);
  }

  /**
   * Initialize shared uniform blocks. These blocks let us centrally
   * manage uniforms that are common to all shaders and are only updated once per run,
   * or once per frame. Keeping a single copy of these uniforms in GPU memory saves space
   * and reduces the number of uniform setup and data transfer calls needed for each
   * running shader.
   */
  private void initializeUniformBlocks() {

    // Allocate backing buffer for per run uniforms in native memory
    // First determine the size of the buffer.  Unfortunately, in Java this
    // needs to be done manually, as we can't directly get the size of the corresponding
    // c-language struct. Fortunately, it's not difficult, but the layout rules
    // must be followed exactly.   If you need to add elements to a uniform block,
    // see the spec at: http://www.opengl.org/registry/specs/ARB/uniform_buffer_object.txt
    //

    // At present,we need 6 floats for per run uniforms:
    // 4 floats for vec4 iMouse
    // 2 floats for vec2 iResolution
    //
    this.perRunUniformBlockSize = getUBOAlignedSize(6);
    this.perRunUniformBlock = GLBuffers.newDirectFloatBuffer(perRunUniformBlockSize / 4);

    // copy data to the perRunUniformBlock buffer
    // iMouse is not used, but is retained for Shadertoy compatibility.
    // We'll just zero it out.
    perRunUniformBlock.put(0, 0f);
    perRunUniformBlock.put(1, 0f);
    perRunUniformBlock.put(2, 0f);
    perRunUniformBlock.put(3, 0f);

    // iResolution is the size of the canvas
    perRunUniformBlock.put(4, (float) xSize);
    perRunUniformBlock.put(5, (float) ySize);

    // Do the same thing for per frame uniforms
    // The items in the block are, in order:
    // 1 float for beat
    // 1 float for sinPhaseBeat
    // 1 float for bassLevel
    // 1 float for trebleLevel
    // 1 float for bassRatio
    // 1 float for trebleRatio
    // 1 float for volumeRatio
    // 1 float for stemBass
    // 1 float for stemDrums
    // 1 float for stemVocals
    // 1 float for stemOther
    // VERY IMPORTANT NOTE: whatever order this buffer is loaded in MUST be replicated exactly
    // in the shader framework code's uniform block declaration. Otherwise, the uniforms will not
    // have the correct values in the shader.
    this.perFrameUniformBlockSize = getUBOAlignedSize(11);
    this.perFrameUniformBlock = GLBuffers.newDirectFloatBuffer(perFrameUniformBlockSize / 4);

    // Generate the uniform block buffers
    gl4.glGenBuffers(2, uniformBlockHandles, 0);

    // Bind the per-run uniform block to the gl buffer object
    gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, uniformBlockHandles[0]);

    // copy the per-run uniform block data to the buffer
    gl4.glBufferData(GL4.GL_UNIFORM_BUFFER, perRunUniformBlockSize, perRunUniformBlock, GL4.GL_DYNAMIC_DRAW);
    gl4.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, perRunUniformBlockBinding, uniformBlockHandles[0], 0, perRunUniformBlockSize);

    // Do the same for the per-frame uniform block and its initial data
    gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, uniformBlockHandles[1]);
    gl4.glBufferData(GL4.GL_UNIFORM_BUFFER, perFrameUniformBlockSize, perFrameUniformBlock, GL4.GL_DYNAMIC_DRAW);
    gl4.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, perFrameUniformBlockBinding, uniformBlockHandles[1], 0, perFrameUniformBlockSize);
  }

  // Update once-per-frame audio data and all the calculated
  // values derived from it.
  private void updateAudioFrameData(double deltaMs) {
    beat = lx.engine.tempo.basis();
    sinPhaseBeat = 0.5 + 0.5 * Math.sin(Math.PI * beat);

    // current instantaneous levels of frequency ranges we're interested in
    volume = Math.max(AUDIO_LEVEL_MIN, meter.getNormalized());
    bassLevel = Math.max(AUDIO_LEVEL_MIN, meter.getAverage(0, 2));
    trebleLevel = Math.max(AUDIO_LEVEL_MIN, meter.getAverage(meter.numBands / 2, meter.numBands / 2));

    // Compute the ratios of current instantaneous levels
    // to their slow EMAs.  See TEAudioPattern.java for more info.
    volumeRatio = volume / avgVolume.update(volume, deltaMs);
    bassRatio = bassLevel / avgBass.update(bassLevel, deltaMs);
    trebleRatio = trebleLevel / avgTreble.update(trebleLevel, deltaMs);
  }

  // update the per-frame shared uniform block with current audio data
  private void updatePerFrameUniforms() {
    perFrameUniformBlock.put((float) beat);                        // beat
    perFrameUniformBlock.put((float) sinPhaseBeat);                // sinPhaseBeat
    perFrameUniformBlock.put((float) bassLevel);                   // bassLevel
    perFrameUniformBlock.put((float) trebleLevel);                 // trebleLevel
    perFrameUniformBlock.put((float) bassRatio);                   // bassRatio
    perFrameUniformBlock.put((float) trebleRatio);                 // trebleRatio
    perFrameUniformBlock.put((float) volumeRatio);                 // volumeRatio
    perFrameUniformBlock.put(AudioStems.get().bass.getValuef());   // stemBass
    perFrameUniformBlock.put(AudioStems.get().drums.getValuef());  // stemDrums
    perFrameUniformBlock.put(AudioStems.get().vocals.getValuef()); // stemVocals
    perFrameUniformBlock.put(AudioStems.get().other.getValuef());  // stemOther

    // update the GPU buffer with the new data
    perFrameUniformBlock.rewind();
    gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, uniformBlockHandles[1]);
    gl4.glBufferSubData(GL4.GL_UNIFORM_BUFFER, 0, perFrameUniformBlockSize, perFrameUniformBlock);
  }

  public GLEngine(LX lx, int width, int height, boolean isStaticModel) {
    current = this;
    // The shape the user gives us affects the rendered aspect ratio,
    // but what really matters is that it needs to have room for the
    // largest number of points we're going to encounter during a run.
    // TODO - adjust buffer size & shape dynamically as the model changes.
    // TODO - this will require a lot of GPU memory management, so is
    // TODO - a longer-term goal.
    xSize = width;
    ySize = height;

    maxPoints = xSize * ySize;
    TE.log("GLEngine: Rendering canvas size: " + xSize + "x" + ySize + " = " + GLEngine.maxPoints + " total points");

    // register glEngine so we can access it from patterns.
    // and add it as an engine task for audio analysis and buffer management
    lx.engine.registerComponent(PATH, this);
    lx.engine.addLoopTask(this);

    this.isStatic = isStaticModel;
    this.model = lx.getModel();

    // set up audio fft and waveform handling
    // TODO - strongly consider expanding the number of FFT bands.
    // TODO - LX defaults to 16, but more bands would let us do more
    // TODO - interesting audio analysis.
    this.meter = lx.engine.audio.meter;
    fftResampleFactor = meter.bands.length / 512f;
  }

  @Override
  public void modelGenerationChanged(LX lx, LXModel model) {
    this.model = model;
    this.modelChanged = true;
  }

  public void loop(double deltaMs) {

    // On first frame...
    if (canvas == null) {
      // create and initialize offscreen drawable for gl rendering
      canvas = ShaderUtils.createGLSurface(xSize, ySize);
      canvas.display();
      gl4 = canvas.getGL().getGL4();

      // activate our context and do initialization tasks
      canvas.getContext().makeCurrent();

      // set up shared uniform blocks
      initializeUniformBlocks();

      // set up the per-frame audio info texture
      initializeAudioTexture();
      textureCache = new TextureManager(gl4);

      // start listening for model changes
      lx.addListener(this);
    }

    // activate our context and do per-frame tasks
    canvas.getContext().makeCurrent();
    updateAudioFrameData(deltaMs);
    updateAudioTexture();
    updatePerFrameUniforms();

    if (modelChanged) {
      // if the model has changed, discard all existing view coordinate textures
      textureCache.clearCoordinateTextures();
      modelChanged = false;
    }
  }

  public void dispose() {
    // free GPU resources that we directly allocated
    gl4.glDeleteTextures(audioTextureHandle.length, audioTextureHandle, 0);
    gl4.glDeleteBuffers(uniformBlockHandles.length, uniformBlockHandles, 0);
  }

  // Static getters for per-frame audio parameters
  // TEPattern and derived pattern classes can access these, which are evaluated
  // only once per frame, instead of recalculating the values for every running pattern
  // instance.
  public static double getAvgVolume() {
    return avgVolume.getValue();
  }

  public static double getAvgBass() {
    return avgBass.getValue();
  }

  public static double getAvgTreble() {
    return avgTreble.getValue();
  }

  public static double getBeat() {
    return beat;
  }

  public static double getSinPhaseOnBeat() {
    return sinPhaseBeat;
  }

  public static double getVolume() {
    return volume;
  }

  public static double getBassLevel() {
    return bassLevel;
  }

  public static double getTrebleLevel() {
    return trebleLevel;
  }

  public static double getVolumeRatio() {
    return volumeRatio;
  }

  public static double getBassRatio() {
    return bassRatio;
  }

  public static double getTrebleRatio() {
    return trebleRatio;
  }

}
