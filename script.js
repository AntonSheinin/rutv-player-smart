let playlist = [];
let currentIndex = 0;
let hls = null;

const videoPlayer = document.getElementById('videoPlayer');
const videoSource = document.getElementById('videoSource');
const videoTitle = document.getElementById('videoTitle');
const playlistContainer = document.getElementById('playlistContainer');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');

function parseM3U8(content) {
    const lines = content.split('\n');
    const channels = [];
    let currentChannel = {};
    
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        
        if (line.startsWith('#EXTINF:')) {
            const nameMatch = line.match(/tvg-name="([^"]+)"/);
            const logoMatch = line.match(/tvg-logo="([^"]+)"/);
            const groupMatch = line.match(/group-title="([^"]+)"/);
            const titleMatch = line.match(/,\s*(.+)$/);
            
            currentChannel = {
                title: nameMatch ? nameMatch[1] : (titleMatch ? titleMatch[1] : 'Unknown'),
                logo: logoMatch ? logoMatch[1] : '',
                group: groupMatch ? groupMatch[1] : 'General'
            };
        } else if (line && !line.startsWith('#') && currentChannel.title) {
            currentChannel.url = line;
            channels.push(currentChannel);
            currentChannel = {};
        }
    }
    
    return channels;
}

async function loadPlaylist() {
    try {
        const response = await fetch('playlist.m3u8');
        const content = await response.text();
        playlist = parseM3U8(content);
        
        if (playlist.length > 0) {
            console.log(`Loaded ${playlist.length} channels from M3U8 playlist`);
            createPlaylistUI();
            loadVideo(0);
        } else {
            videoTitle.textContent = 'No channels found in playlist';
        }
    } catch (error) {
        console.error('Error loading playlist:', error);
        videoTitle.textContent = 'Error loading playlist';
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
            xhrSetup: function(xhr, url) {
                if (url.startsWith('http://')) {
                    xhr.open('GET', `/proxy?url=${encodeURIComponent(url)}`, true);
                }
            }
        });
        
        hls.loadSource(streamUrl);
        hls.attachMedia(videoPlayer);
        
        hls.on(Hls.Events.MANIFEST_PARSED, function() {
            videoPlayer.play().catch(error => {
                console.error('Playback error:', error);
                updateStatusError(currentIndex, 'Playback failed');
            });
        });
        
        hls.on(Hls.Events.ERROR, function(event, data) {
            if (data.fatal) {
                console.error('Fatal HLS error:', data);
                updateStatusError(currentIndex, 'Stream error');
                switch(data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        console.log('Network error, trying to recover...');
                        hls.startLoad();
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        console.log('Media error, trying to recover...');
                        hls.recoverMediaError();
                        break;
                    default:
                        hls.destroy();
                        break;
                }
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

loadPlaylist();
