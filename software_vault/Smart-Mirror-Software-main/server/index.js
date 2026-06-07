require('dotenv').config();
const express = require('express');
const querystring = require('querystring');
const fetch = require('node-fetch');
const Parser = require('rss-parser');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

const {
  SPOTIFY_CLIENT_ID,
  SPOTIFY_CLIENT_SECRET,
  PORT = 8888
} = process.env;

let spotifyToken = null;
let tokenExpiresAt = 0;

// Refresh Spotify Token
async function refreshToken() {
  const now = Date.now();
  if (spotifyToken && now < tokenExpiresAt - 60000) {
    return spotifyToken;
  }

  const resp = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: {
      Authorization: 'Basic ' + Buffer.from(`${SPOTIFY_CLIENT_ID}:${SPOTIFY_CLIENT_SECRET}`).toString('base64'),
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: querystring.stringify({ grant_type: 'client_credentials' })
  });
  const data = await resp.json();
  spotifyToken = data.access_token;
  tokenExpiresAt = now + data.expires_in * 1000;
  return spotifyToken;
}

// Endpoint for frontend to get fresh token
app.get('/api/spotify-token', async (req, res) => {
  try {
    const token = await refreshToken();
    res.json({ access_token: token });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'token_error' });
  }
});

// News aggregator (RSS → JSON)
const rssParser = new Parser();
const sources = {
  general: [
    'https://feeds.bbci.co.uk/news/rss.xml',
    'https://feeds.skynews.com/feeds/rss/home.xml'
  ],
  gaming: [
    'https://www.ign.com/articles/rss',
    'https://www.eurogamer.net/?format=rss',
    'https://www.gamespot.com/feeds/news/'
  ],
  sports: [
    'https://www.formula1.com/content/fom-website/en/latest.rss',
    'http://feeds.bbci.co.uk/sport/rss.xml?edition=uk',
    'https://www.skysports.com/rss/12040',
    'https://www.espn.com/espn/rss/news'
  ],
  entertainment: [
    'https://www.rottentomatoes.com/syndication/rss/top_movies.rss',
    'https://rss.imdb.com/news/film.rss',
    'https://variety.com/feed/',
    'https://collider.com/feed/'
  ]
};

app.get('/api/news', async (req, res) => {
  const category = req.query.category || 'general';
  const feeds = sources[category] || sources.general;
  try {
    const all = await Promise.all(feeds.map(url => rssParser.parseURL(url)));
    const items = all.flatMap(feed => feed.items.slice(0, 5));
    items.sort((a, b) => new Date(b.pubDate) - new Date(a.pubDate));
    res.json(items.slice(0, 10));
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'news_error' });
  }
});

// Crypto prices (CoinGecko)
app.get('/api/crypto', async (req, res) => {
  try {
    const resp = await fetch('https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd');
    const data = await resp.json();
    res.json(data);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'crypto_error' });
  }
});

app.listen(PORT, () => console.log(`Server running on http://localhost:${PORT}`));
