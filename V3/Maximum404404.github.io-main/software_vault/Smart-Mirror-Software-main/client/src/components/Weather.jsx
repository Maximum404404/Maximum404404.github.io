import React, { useEffect, useState } from 'react';
import axios from 'axios';

const Weather = () => {
  const [weather, setWeather] = useState(null);

  useEffect(() => {
    axios.get('https://ipinfo.io/json?token=YOUR_IPINFO_TOKEN')
      .then(res => {
        const [lat, lon] = res.data.loc.split(',');
        return axios.get(`https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&appid=YOUR_OPENWEATHERMAP_KEY&units=metric`);
      })
      .then(res => setWeather(res.data));
  }, []);

  const getEmoji = (main) => {
    switch (main) {
      case 'Clear': return '☀️';
      case 'Rain': return '🌧️';
      case 'Snow': return '❄️';
      case 'Thunderstorm': return '🌩️';
      case 'Clouds': return '☁️';
      default: return '🌡️';
    }
  };

  return (
    <div className="text-white absolute top-20 right-4 text-sm">
      {weather && (
        <>
          {getEmoji(weather.weather[0].main)} {weather.weather[0].main}, {Math.round(weather.main.temp)}°C
        </>
      )}
    </div>
  );
};

export default Weather;
