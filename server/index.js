const express = require('express');
const app = express();

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const VALID_TOKENS = new Map();
VALID_TOKENS.set('wLaPEFi23KFwI0', {
  userId: 'user1',
  maxSessions: 3,
  duration: 3600,
  allowedStreams: ['*']
});

function logRequest(req, decision) {
  const timestamp = new Date().toISOString();
  const params = req.method === 'POST' ? req.body : req.query;
  console.log(`[${timestamp}] ${decision} - Token: ${params.token || 'none'}, IP: ${params.ip || 'unknown'}, Stream: ${params.name || 'unknown'}`);
}

app.all('/auth', (req, res) => {
  const params = req.method === 'POST' ? req.body : req.query;
  
  const {
    token,
    ip,
    name: streamName,
    user_agent: userAgent,
    referer
  } = params;

  if (!token) {
    logRequest(req, 'DENIED (no token)');
    return res.status(403).json({ error: 'No token provided' });
  }

  const tokenData = VALID_TOKENS.get(token);
  
  if (!tokenData) {
    logRequest(req, 'DENIED (invalid token)');
    return res.status(403).json({ error: 'Invalid token' });
  }

  if (tokenData.allowedStreams && !tokenData.allowedStreams.includes('*')) {
    if (!tokenData.allowedStreams.includes(streamName)) {
      logRequest(req, 'DENIED (stream not allowed)');
      return res.status(403).json({ error: 'Stream not allowed for this token' });
    }
  }

  logRequest(req, 'ALLOWED');
  
  res.set({
    'X-AuthDuration': tokenData.duration || 600,
    'X-Max-Sessions': tokenData.maxSessions || 3
  });
  
  res.status(200).json({
    user_id: tokenData.userId,
    duration: tokenData.duration,
    max_sessions: tokenData.maxSessions
  });
});

app.post('/tokens', (req, res) => {
  const { token, userId, maxSessions = 3, duration = 3600, allowedStreams = ['*'] } = req.body;
  
  if (!token || !userId) {
    return res.status(400).json({ error: 'Token and userId required' });
  }
  
  VALID_TOKENS.set(token, { userId, maxSessions, duration, allowedStreams });
  
  console.log(`[${new Date().toISOString()}] Token added: ${token} for user ${userId}`);
  
  res.status(201).json({ 
    message: 'Token created successfully',
    token,
    userId,
    maxSessions,
    duration,
    allowedStreams
  });
});

app.delete('/tokens/:token', (req, res) => {
  const { token } = req.params;
  
  if (VALID_TOKENS.has(token)) {
    VALID_TOKENS.delete(token);
    console.log(`[${new Date().toISOString()}] Token deleted: ${token}`);
    res.json({ message: 'Token deleted successfully' });
  } else {
    res.status(404).json({ error: 'Token not found' });
  }
});

app.get('/tokens', (req, res) => {
  const tokens = Array.from(VALID_TOKENS.entries()).map(([token, data]) => ({
    token,
    ...data
  }));
  
  res.json({ tokens, count: tokens.length });
});

app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    uptime: process.uptime(),
    activeTokens: VALID_TOKENS.size
  });
});

const PORT = process.env.PORT || 5000;

app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n🔐 Flussonic Auth Backend running on port ${PORT}`);
  console.log(`\nAuth endpoint: http://localhost:${PORT}/auth`);
  console.log(`Token management: http://localhost:${PORT}/tokens`);
  console.log(`\nPre-configured tokens: ${VALID_TOKENS.size}`);
  console.log(`\nFlussonic config example:`);
  console.log(`  auth_backend myauth {`);
  console.log(`    backend http://YOUR_SERVER_IP:${PORT}/auth;`);
  console.log(`  }`);
  console.log(`  stream example {`);
  console.log(`    on_play auth://myauth;`);
  console.log(`  }\n`);
});
