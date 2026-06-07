import React, { useEffect, useState } from 'react';

const Clock = () => {
  const [time, setTime] = useState('');
  const [day, setDay] = useState('');
  const [date, setDate] = useState('');

  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      const hours = now.getHours();
      const minutes = now.getMinutes();
      const seconds = now.getSeconds();
      const ampm = hours >= 12 ? 'PM' : 'AM';
      const h = hours % 12 || 12;

      setTime(`\${String(h).padStart(2,'0')}:\${String(minutes).padStart(2,'0')}:\${String(seconds).padStart(2,'0')} \${ampm}`);
      setDay(now.toLocaleDateString('en-US',{ weekday: 'long' }));
      setDate(now.toLocaleDateString('en-GB'));
    };

    updateClock();
    const interval = setInterval(updateClock, 1000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="absolute top-4 right-4 text-white text-right">
      <div className="text-3xl font-bold">{time}</div>
      <div className="text-md">{day}</div>
      <div className="text-md">{date}</div>
    </div>
  );
};

export default Clock;
