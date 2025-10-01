const playlist = [
    {
        title: "Big Buck Bunny",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    },
    {
        title: "Elephant Dream",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
    },
    {
        title: "For Bigger Blazes",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
    },
    {
        title: "For Bigger Escape",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
    },
    {
        title: "For Bigger Fun",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"
    },
    {
        title: "Sintel",
        url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
    }
];

let currentIndex = 0;

const videoPlayer = document.getElementById('videoPlayer');
const videoSource = document.getElementById('videoSource');
const videoTitle = document.getElementById('videoTitle');
const playlistContainer = document.getElementById('playlistContainer');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');

function createPlaylistUI() {
    playlistContainer.innerHTML = '';
    
    playlist.forEach((video, index) => {
        const item = document.createElement('div');
        item.className = 'playlist-item';
        item.innerHTML = `
            <div class="playlist-item-title">${video.title}</div>
            <div class="playlist-item-status">Ready</div>
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
    
    videoSource.src = video.url;
    videoTitle.textContent = video.title;
    videoPlayer.load();
    videoPlayer.play();
    
    updatePlaylistUI();
    updateButtons();
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

createPlaylistUI();
loadVideo(0);
