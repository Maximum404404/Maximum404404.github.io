import React, { useEffect, useState } from 'react';
import axios from 'axios';

const SpotifyNow = () => {
  const [track, setTrack] = useState(null);

  useEffect(() => {
    const fetchNow = async () => {
      const { data: { access_token } } = await axios.get('/api/spotify-token');
      const now = await axios.get('https://api.spotify.com/v1/me/player/currently-playing', {
        headers: { Authorization: `Bearer ${access_token}` }
      });
      setTrack(now.data);
    };
    fetchNow();
    const interval = setInterval(fetchNow, 15000);
    return () => clearInterval(interval);
  }, []);

  if (!track || track.status === 204) return <p className="text-white">No song playing</p>;

  const item = track.item;
  const progress = (track.progress_ms / item.duration_ms) * 100;

  return (
    <div className="p-4 bg-gray-800 text-white rounded-lg">
      <img src={item.album.images[0].url} alt="" className="w-16 h-16 inline-block mr-4" />
      <div className="inline-block align-top">
        <p className="font-bold">{item.name}</p>
        <p className="text-sm">{item.artists.map(a => a.name).join(', ')}</p>
        <div className="w-48 h-2 bg-gray-600 rounded mt-2">
          <div className="h-2 bg-green-400 rounded" style={{ width: `${progress}%` }} />
        </div>
      </div>
    </div>
  );
};

export default SpotifyNow;
