package com.limelight.nvstream;

import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Everything negotiated with the host before a stream starts: resolution, frame rate, bitrate,
 * codecs, audio configuration, colour space and encryption.
 *
 * <p>Built with the nested {@link Builder} and then immutable in practice. The values come from
 * three places that have to agree — the user's preferences, what the decoder reports it can
 * handle, and what the host supports — which is why this is assembled once in {@code Game} rather
 * than read from preferences where it is needed.
 */
public class StreamConfiguration {
    public static final int INVALID_APP_ID = 0;

    public static final int STREAM_CFG_LOCAL = 0;
    public static final int STREAM_CFG_REMOTE = 1;
    public static final int STREAM_CFG_AUTO = 2;
    
    private NvApp app;
    private int width, height;
    private int refreshRate;
    private int launchRefreshRate;
    private int clientRefreshRateX100;
    private int bitrate;
    private boolean sops;
    private boolean enableAdaptiveResolution;
    private boolean playLocalAudio;
    private int maxPacketSize;
    private int remote;
    private MoonBridge.AudioConfiguration audioConfiguration;
    private int supportedVideoFormats;
    private int attachedGamepadMask;
    private int encryptionFlags;
    private int colorRange;
    private int colorSpace;
    private boolean persistGamepadsAfterDisconnect;

    public static class Builder {
        private StreamConfiguration config = new StreamConfiguration();
        
        public StreamConfiguration.Builder setApp(NvApp app) {
            config.app = app;
            return this;
        }
        
        public StreamConfiguration.Builder setRemoteConfiguration(int remote) {
            config.remote = remote;
            return this;
        }
        
        public StreamConfiguration.Builder setResolution(int width, int height) {
            config.width = width;
            config.height = height;
            return this;
        }
        
        public StreamConfiguration.Builder setRefreshRate(int refreshRate) {
            config.refreshRate = refreshRate;
            return this;
        }

        public StreamConfiguration.Builder setLaunchRefreshRate(int refreshRate) {
            config.launchRefreshRate = refreshRate;
            return this;
        }
        
        public StreamConfiguration.Builder setBitrate(int bitrate) {
            config.bitrate = bitrate;
            return this;
        }
        
        public StreamConfiguration.Builder setEnableSops(boolean enable) {
            config.sops = enable;
            return this;
        }
        
        public StreamConfiguration.Builder enableAdaptiveResolution(boolean enable) {
            config.enableAdaptiveResolution = enable;
            return this;
        }
        
        public StreamConfiguration.Builder enableLocalAudioPlayback(boolean enable) {
            config.playLocalAudio = enable;
            return this;
        }
        
        public StreamConfiguration.Builder setMaxPacketSize(int maxPacketSize) {
            config.maxPacketSize = maxPacketSize;
            return this;
        }

        public StreamConfiguration.Builder setAttachedGamepadMask(int attachedGamepadMask) {
            config.attachedGamepadMask = attachedGamepadMask;
            return this;
        }

        public StreamConfiguration.Builder setPersistGamepadsAfterDisconnect(boolean value) {
            config.persistGamepadsAfterDisconnect = value;
            return this;
        }

        public StreamConfiguration.Builder setClientRefreshRateX100(int refreshRateX100) {
            config.clientRefreshRateX100 = refreshRateX100;
            return this;
        }

        public StreamConfiguration.Builder setAudioConfiguration(MoonBridge.AudioConfiguration audioConfig) {
            config.audioConfiguration = audioConfig;
            return this;
        }
        
        public StreamConfiguration.Builder setSupportedVideoFormats(int supportedVideoFormats) {
            config.supportedVideoFormats = supportedVideoFormats;
            return this;
        }

        public StreamConfiguration.Builder setColorRange(int colorRange) {
            config.colorRange = colorRange;
            return this;
        }

        public StreamConfiguration.Builder setColorSpace(int colorSpace) {
            config.colorSpace = colorSpace;
            return this;
        }

        public StreamConfiguration build() {
            return config;
        }
    }
    
    private StreamConfiguration() {
        // Set default attributes
        this.app = new NvApp("Steam");
        this.width = 1280;
        this.height = 720;
        this.refreshRate = 60;
        this.launchRefreshRate = 60;
        this.bitrate = 10000;
        this.maxPacketSize = 1024;
        this.remote = STREAM_CFG_AUTO;
        this.sops = true;
        this.enableAdaptiveResolution = false;
        this.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        this.supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        this.attachedGamepadMask = 0;
    }
    
    /** @return stream width in pixels */
    public int getWidth() {
        return width;
    }
    
    /** @return stream height in pixels */
    public int getHeight() {
        return height;
    }
    
    /** @return stream frame rate in Hz */
    public int getRefreshRate() {
        return refreshRate;
    }

    /** @return the frame rate requested at launch, which can differ from the streaming rate */
    public int getLaunchRefreshRate() {
        return launchRefreshRate;
    }
    
    /** @return video bitrate in Kbps */
    public int getBitrate() {
        return bitrate;
    }
    
    /** @return maximum packet size, reduced for remote connections to avoid fragmentation */
    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    /** @return the app to launch */
    public NvApp getApp() {
        return app;
    }
    
    /** @return whether the host may change its own resolution to match the stream */
    public boolean getSops() {
        return sops;
    }
    
    
    /** @return whether audio also plays on the host's speakers */
    public boolean getPlayLocalAudio() {
        return playLocalAudio;
    }
    
    /** @return whether this connection is local, remote, or auto-detected */
    public int getRemote() {
        return remote;
    }

    /** @return the negotiated channel count and channel mask */
    public MoonBridge.AudioConfiguration getAudioConfiguration() {
        return audioConfiguration;
    }
    
    /** @return bitmask of codecs the client can decode */
    public int getSupportedVideoFormats() {
        return supportedVideoFormats;
    }

    /** @return bitmask of controllers attached at launch, so the host creates the right slots */
    public int getAttachedGamepadMask() {
        return attachedGamepadMask;
    }

    /** @return whether host-side controllers persist after the stream ends */
    public boolean getPersistGamepadsAfterDisconnect() {
        return persistGamepadsAfterDisconnect;
    }

    /** @return the client display's refresh rate in hundredths of a Hz, for host-side pacing */
    public int getClientRefreshRateX100() {
        return clientRefreshRateX100;
    }

    /** @return full or limited colour range */
    public int getColorRange() {
        return colorRange;
    }

    /** @return the requested colourspace */
    public int getColorSpace() {
        return colorSpace;
    }
}
