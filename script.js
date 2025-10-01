let playlist = [];
let currentIndex = 0;
let hls = null;

const videoPlayer = document.getElementById('videoPlayer');
const videoSource = document.getElementById('videoSource');
const videoTitle = document.getElementById('videoTitle');
const playlistContainer = document.getElementById('playlistContainer');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const fileInput = document.getElementById('fileInput');
const urlInput = document.getElementById('urlInput');
const loadUrlBtn = document.getElementById('loadUrlBtn');

function parseM3U8(content) {
    console.log('Parsing M3U/M3U8 content, length:', content.length);
    
    const trimmed = content.trim().toLowerCase();
    if ((trimmed.startsWith('<!doctype') || trimmed.startsWith('<html')) && 
        (trimmed.includes('<head>') || trimmed.includes('<body>'))) {
        console.error('Content appears to be HTML, not M3U/M3U8');
        throw new Error('Received HTML page instead of playlist. The URL may be blocked or invalid.');
    }
    
    if (!trimmed.startsWith('#extm3u') && !trimmed.includes('http')) {
        console.error('Content does not appear to be a valid M3U/M3U8 playlist');
        throw new Error('Invalid playlist format. File must start with #EXTM3U or contain stream URLs.');
    }
    
    const lines = content.split('\n');
    const channels = [];
    let currentChannel = {};
    let channelIndex = 1;
    
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        
        if (line.startsWith('#EXTINF:')) {
            const nameMatch = line.match(/tvg-name="([^"]+)"/);
            const logoMatch = line.match(/tvg-logo="([^"]+)"/);
            const groupMatch = line.match(/group-title="([^"]+)"/);
            const titleMatch = line.match(/,\s*(.+)$/);
            
            currentChannel = {
                title: nameMatch ? nameMatch[1] : (titleMatch ? titleMatch[1] : `Channel ${channelIndex}`),
                logo: logoMatch ? logoMatch[1] : '',
                group: groupMatch ? groupMatch[1] : 'General'
            };
        } else if (line && !line.startsWith('#') && (line.startsWith('http') || line.includes('.'))) {
            if (currentChannel.title) {
                currentChannel.url = line;
                channels.push(currentChannel);
                console.log('Parsed channel:', currentChannel.title);
                currentChannel = {};
                channelIndex++;
            } else {
                const urlFromPath = line.split('/').pop().split('?')[0];
                const titleFromUrl = urlFromPath.replace(/\.(m3u8?|ts|mp4)$/i, '').replace(/[_-]/g, ' ');
                channels.push({
                    title: titleFromUrl || `Channel ${channelIndex}`,
                    url: line,
                    logo: '',
                    group: 'General'
                });
                console.log('Parsed simple URL:', line);
                channelIndex++;
            }
        }
    }
    
    console.log('Total channels parsed:', channels.length);
    return channels;
}

async function loadPlaylist(source = 'playlist.m3u8') {
    try {
        let content;
        
        if (typeof source === 'string') {
            const response = await fetch(source);
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            content = await response.text();
        } else {
            content = source;
        }
        
        playlist = parseM3U8(content);
        
        if (playlist.length > 0) {
            console.log(`Loaded ${playlist.length} channels from M3U8 playlist`);
            createPlaylistUI();
            loadVideo(0);
            videoTitle.textContent = playlist[0].title;
        } else {
            videoTitle.textContent = 'No channels found in playlist';
        }
    } catch (error) {
        console.error('Error loading playlist:', error);
        videoTitle.textContent = 'Error loading playlist: ' + error.message;
        alert('Failed to load playlist: ' + error.message);
    }
}

function loadPlaylistFromFile(file) {
    const reader = new FileReader();
    reader.onload = function(e) {
        const content = e.target.result;
        loadPlaylist(content);
    };
    reader.onerror = function() {
        alert('Error reading file');
    };
    reader.readAsText(file);
}

async function loadPlaylistFromURL(url) {
    if (!url) {
        alert('Please enter a URL');
        return;
    }
    
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
        alert('URL must start with http:// or https://');
        return;
    }
    
    loadUrlBtn.disabled = true;
    loadUrlBtn.textContent = 'Loading...';
    
    try {
        let fetchUrl = url;
        if (url.startsWith('http://')) {
            fetchUrl = `/proxy?url=${encodeURIComponent(url)}`;
        }
        
        await loadPlaylist(fetchUrl);
        urlInput.value = '';
    } catch (error) {
        console.error('Error loading from URL:', error);
    } finally {
        loadUrlBtn.disabled = false;
        loadUrlBtn.textContent = 'Load URL';
    }
}

function createPlaylistUI() {
    playlistContainer.innerHTML = '';
    
    playlist.forEach((video, index) => {
        const item = document.createElement('div');
        item.className = 'playlist-item';
        
        let logoHtml = '';
        if (video.logo) {
            logoHtml = `<img src="${video.logo}" alt="${video.title}" class="channel-logo" onerror="this.style.display='none'">`;
        }
        
        item.innerHTML = `
            ${logoHtml}
            <div class="playlist-item-content">
                <div class="playlist-item-title">${video.title}</div>
                <div class="playlist-item-group">${video.group}</div>
                <div class="playlist-item-status">Ready</div>
            </div>
        `;
        
        item.addEventListener('click', () => {
            loadVideo(index);
        });
        
        playlistContainer.appendChild(item);
    });
}

function updatePlaylistUI() {
    const items = document.querySelectorAll('.playlist-item');
    items.forEach((item, index) => {
        item.classList.remove('active', 'playing');
        const statusEl = item.querySelector('.playlist-item-status');
        
        if (index === currentIndex) {
            item.classList.add('playing');
            statusEl.textContent = '▶ Playing';
        } else {
            statusEl.textContent = 'Ready';
        }
    });
}

function loadVideo(index) {
    if (index < 0 || index >= playlist.length) return;
    
    currentIndex = index;
    const video = playlist[currentIndex];
    
    videoTitle.textContent = video.title;
    
    if (hls) {
        hls.destroy();
        hls = null;
    }
    
    const isHLS = video.url.includes('.m3u8') || video.url.includes('application/x-mpegURL');
    
    let streamUrl = video.url;
    if (streamUrl.startsWith('http://')) {
        streamUrl = `/proxy?url=${encodeURIComponent(streamUrl)}`;
    }
    
    if (Hls.isSupported() && isHLS) {
        hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
            debug: false,
            maxBufferLength: 30,
            maxMaxBufferLength: 600,
            maxBufferSize: 60 * 1000 * 1000,
            maxBufferHole: 0.5,
            highBufferWatchdogPeriod: 2,
            nudgeOffset: 0.1,
            nudgeMaxRetry: 3,
            maxFragLookUpTolerance: 0.25,
            liveSyncDurationCount: 3,
            liveMaxLatencyDurationCount: Infinity,
            liveDurationInfinity: false,
            enableWebVTT: true,
            enableIMSC1: true,
            enableCEA708Captions: true,
            stretchShortVideoTrack: false,
            maxAudioFramesDrift: 1,
            forceKeyFrameOnDiscontinuity: true,
            abrEwmaFastLive: 3.0,
            abrEwmaSlowLive: 9.0,
            abrEwmaFastVoD: 3.0,
            abrEwmaSlowVoD: 9.0,
            abrEwmaDefaultEstimate: 500000,
            abrBandWidthFactor: 0.95,
            abrBandWidthUpFactor: 0.7,
            abrMaxWithRealBitrate: false,
            maxStarvationDelay: 4,
            maxLoadingDelay: 4,
            minAutoBitrate: 0,
            emeEnabled: false,
            widevineLicenseUrl: undefined,
            drmSystems: {},
            requestMediaKeySystemAccessFunc: null,
            testBandwidth: true,
            progressive: false,
            lowLatencyMode: false,
            fpsDroppedMonitoringPeriod: 5000,
            fpsDroppedMonitoringThreshold: 0.2,
            appendErrorMaxRetry: 3,
            loader: Hls.DefaultConfig.loader,
            fLoader: undefined,
            pLoader: undefined,
            xhrSetup: function(xhr, url) {
                if (url.startsWith('http://')) {
                    xhr.open('GET', `/proxy?url=${encodeURIComponent(url)}`, true);
                }
            },
            fetchSetup: undefined,
            abrController: Hls.DefaultConfig.abrController,
            timelineController: Hls.DefaultConfig.timelineController,
            enableSoftwareAES: true,
            manifestLoadingTimeOut: 10000,
            manifestLoadingMaxRetry: 1,
            manifestLoadingRetryDelay: 1000,
            manifestLoadingMaxRetryTimeout: 64000,
            startLevel: undefined,
            levelLoadingTimeOut: 10000,
            levelLoadingMaxRetry: 4,
            levelLoadingRetryDelay: 1000,
            levelLoadingMaxRetryTimeout: 64000,
            fragLoadingTimeOut: 20000,
            fragLoadingMaxRetry: 6,
            fragLoadingRetryDelay: 1000,
            fragLoadingMaxRetryTimeout: 64000,
            startFragPrefetch: false,
            testBandwidth: true,
            progressive: false,
            lowLatencyMode: false
        });
        
        hls.loadSource(streamUrl);
        hls.attachMedia(videoPlayer);
        
        hls.on(Hls.Events.MANIFEST_PARSED, function(event, data) {
            console.log('Manifest parsed, levels:', data.levels);
            videoPlayer.play().catch(error => {
                console.error('Playback error:', error);
                updateStatusError(currentIndex, 'Playback failed');
            });
        });
        
        hls.on(Hls.Events.FRAG_LOADED, function(event, data) {
            console.log('Fragment loaded:', data.frag.type, data.frag.sn);
        });
        
        hls.on(Hls.Events.BUFFER_APPENDED, function(event, data) {
            console.log('Buffer appended:', data.type);
        });
        
        hls.on(Hls.Events.ERROR, function(event, data) {
            console.log('HLS Error:', data.type, data.details, data.fatal);
            
            if (data.fatal) {
                console.error('Fatal HLS error:', data);
                updateStatusError(currentIndex, 'Stream error');
                switch(data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        console.log('Network error, trying to recover...');
                        setTimeout(() => {
                            hls.startLoad();
                        }, 1000);
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        console.log('Media error, trying to recover...');
                        hls.recoverMediaError();
                        break;
                    default:
                        console.log('Unrecoverable error, destroying player');
                        hls.destroy();
                        break;
                }
            } else {
                console.log('Non-fatal error, continuing playback');
            }
        });
    } else if (videoPlayer.canPlayType('application/vnd.apple.mpegurl') && isHLS) {
        videoSource.src = video.url;
        videoPlayer.load();
        videoPlayer.play().catch(error => {
            console.error('Playback error:', error);
            updateStatusError(currentIndex, 'Playback failed');
        });
    } else {
        videoSource.src = video.url;
        videoSource.type = 'video/mp4';
        videoPlayer.load();
        videoPlayer.play().catch(error => {
            console.error('Playback error:', error);
            updateStatusError(currentIndex, 'Playback failed');
        });
    }
    
    updatePlaylistUI();
    updateButtons();
    
    const playingItem = document.querySelectorAll('.playlist-item')[currentIndex];
    if (playingItem) {
        playingItem.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
}

function updateStatusError(index, message) {
    const statusEl = document.querySelectorAll('.playlist-item')[index]?.querySelector('.playlist-item-status');
    if (statusEl) {
        statusEl.textContent = message;
        statusEl.style.color = '#ff4444';
    }
}

function updateButtons() {
    prevBtn.disabled = currentIndex === 0;
    nextBtn.disabled = currentIndex === playlist.length - 1;
}

prevBtn.addEventListener('click', () => {
    if (currentIndex > 0) {
        loadVideo(currentIndex - 1);
    }
});

nextBtn.addEventListener('click', () => {
    if (currentIndex < playlist.length - 1) {
        loadVideo(currentIndex + 1);
    }
});

videoPlayer.addEventListener('ended', () => {
    if (currentIndex < playlist.length - 1) {
        loadVideo(currentIndex + 1);
    } else {
        loadVideo(0);
    }
});

videoPlayer.addEventListener('error', (e) => {
    console.error('Video error:', e);
    updateStatusError(currentIndex, 'Error loading');
});

if (fileInput) {
    fileInput.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
            loadPlaylistFromFile(file);
        }
    });
}

if (loadUrlBtn && urlInput) {
    loadUrlBtn.addEventListener('click', () => {
        const url = urlInput.value.trim();
        loadPlaylistFromURL(url);
    });

    urlInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            const url = urlInput.value.trim();
            loadPlaylistFromURL(url);
        }
    });
}

loadPlaylist();
