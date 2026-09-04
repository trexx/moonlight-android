package com.limelight.binding.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GPU identification from the {@code GL_RENDERER} string.
 *
 * <p>Getting a model number wrong here silently changes which decoder quirks apply, and the only
 * way to notice on-device is that streaming misbehaves on that one SoC. The renderer strings below
 * are the real formats Android reports.
 */
class GlRendererParserTest {

    @Nested
    @DisplayName("getAdrenoVersionString()")
    class AdrenoVersionString {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "'Adreno (TM) 630',                        630",
                "'Adreno (TM) 640',                        640",
                "'Adreno (TM) 505',                        505",
                "'Adreno (TM) 620',                        620",
                "'Adreno (TM) 306',                        306",
                // Case and surrounding whitespace are both normalised away
                "'ADRENO (TM) 730',                        730",
                "'adreno (tm) 730',                        730",
                "'   Adreno (TM) 650   ',                  650",
        })
        @DisplayName("extracts the model number")
        void extractsModelNumber(String glRenderer, String expected) {
            assertEquals(expected, GlRendererParser.getAdrenoVersionString(glRenderer));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "Mali-G78",
                "Mali-T860",
                "PowerVR Rogue GE8320",
                "NVIDIA Tegra",
                "Android Emulator OpenGL ES Translator",
                // The default before the GL probe has run - GlPreferences seeds glRenderer with ""
                "",
        })
        @DisplayName("returns null for non-Adreno renderers")
        void returnsNullForNonAdreno(String glRenderer) {
            assertNull(GlRendererParser.getAdrenoVersionString(glRenderer));
        }

        @Test
        @DisplayName("returns null for an Adreno string with no three-digit model")
        void returnsNullWithoutModelNumber() {
            assertNull(GlRendererParser.getAdrenoVersionString("Adreno (TM)"));
            assertNull(GlRendererParser.getAdrenoVersionString("Adreno 42"));
        }

        @Test
        @DisplayName("takes the last three-digit run, so a GLES version prefix does not win")
        void takesLastThreeDigitRun() {
            // Real strings often carry the GLES version ahead of the model. The greedy first
            // capture group is what makes the model win; a reluctant one would return "320" here.
            assertEquals("640", GlRendererParser.getAdrenoVersionString("OpenGL ES 3.2 Adreno (TM) 640"));
        }
    }

    @Nested
    @DisplayName("isLowEndSnapdragonRenderer()")
    class LowEndSnapdragon {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                // A zero in the x0x place marks the low-end parts
                "'Adreno (TM) 505', true",
                "'Adreno (TM) 506', true",
                "'Adreno (TM) 405', true",
                "'Adreno (TM) 306', true",
                "'Adreno (TM) 608', true",
                // ...and anything else is not
                "'Adreno (TM) 630', false",
                "'Adreno (TM) 640', false",
                "'Adreno (TM) 730', false",
                "'Adreno (TM) 620', false",
                // Not an Adreno at all
                "'Mali-G78',        false",
                "'',                false",
        })
        void classifiesLowEndParts(String glRenderer, boolean expected) {
            assertEquals(expected, GlRendererParser.isLowEndSnapdragonRenderer(glRenderer));
        }
    }

    @Nested
    @DisplayName("getAdrenoRendererModelNumber()")
    class ModelNumber {

        @Test
        @DisplayName("parses the model number as an int")
        void parsesAsInt() {
            assertEquals(630, GlRendererParser.getAdrenoRendererModelNumber("Adreno (TM) 630"));
            assertEquals(505, GlRendererParser.getAdrenoRendererModelNumber("Adreno (TM) 505"));
        }

        @Test
        @DisplayName("returns -1 for non-Adreno renderers")
        void returnsMinusOneForNonAdreno() {
            assertEquals(-1, GlRendererParser.getAdrenoRendererModelNumber("Mali-G78"));
            assertEquals(-1, GlRendererParser.getAdrenoRendererModelNumber(""));
        }

        @Test
        @DisplayName("identifies the Adreno 620 that MediaCodecHelper special-cases")
        void identifiesAdreno620() {
            // MediaCodecHelper.initialize() compares this against 620 exactly, so an off-by-one
            // in the parse would silently drop that device's quirk.
            assertEquals(620, GlRendererParser.getAdrenoRendererModelNumber("Adreno (TM) 620"));
        }
    }

    @Nested
    @DisplayName("isGLES31SnapdragonRenderer()")
    class Gles31 {

        @Test
        @DisplayName("treats Adreno 4xx and above as GLES 3.1 capable")
        void treatsAdreno4xxAndAboveAsCapable() {
            // The Honor 5x (Snapdragon 616, Adreno 405) reports only GLES 3.0 despite supporting
            // 3.1, which is the whole reason this check exists.
            assertTrue(GlRendererParser.isGLES31SnapdragonRenderer("Adreno (TM) 405"));
            assertTrue(GlRendererParser.isGLES31SnapdragonRenderer("Adreno (TM) 630"));
            assertTrue(GlRendererParser.isGLES31SnapdragonRenderer("Adreno (TM) 400"));
        }

        @Test
        @DisplayName("treats Adreno 3xx and below as not capable")
        void treatsAdreno3xxAsNotCapable() {
            assertFalse(GlRendererParser.isGLES31SnapdragonRenderer("Adreno (TM) 306"));
            assertFalse(GlRendererParser.isGLES31SnapdragonRenderer("Adreno (TM) 399"));
        }

        @Test
        @DisplayName("does not treat a non-Adreno renderer as capable")
        void nonAdrenoIsNotCapable() {
            // Guards the -1 sentinel: a non-Adreno part must not accidentally clear the >= 400 bar
            assertFalse(GlRendererParser.isGLES31SnapdragonRenderer("Mali-G78"));
            assertFalse(GlRendererParser.isGLES31SnapdragonRenderer(""));
        }
    }

    @Nested
    @DisplayName("isPowerVR()")
    class PowerVR {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "'PowerVR Rogue GE8320',   true",
                "'PowerVR SGX 544MP',      true",
                "'powervr rogue gx6250',   true",
                "'POWERVR ROGUE',          true",
                "'Adreno (TM) 630',        false",
                "'Mali-G78',               false",
                "'',                       false",
        })
        void detectsPowerVrRenderers(String glRenderer, boolean expected) {
            assertEquals(expected, GlRendererParser.isPowerVR(glRenderer));
        }
    }
}
