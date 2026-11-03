/**
 * MovieClash — Native Retro Cinema Confetti Engine
 * Zero-dependency HTML5 Canvas confetti with physics, multi-cannon blast,
 * 3D ribbon tilt, and cinema/Friends color palette.
 */

const CINEMA_PALETTE = [
  '#e5a93b', // Marquee Gold
  '#ffd700', // Bright Gold
  '#f5c518', // Warm Mustard Yellow
  '#d32f2f', // Velvet Crimson
  '#e50914', // Cinema Red
  '#6b3ba7', // Friends Purple
  '#8e44ad', // Deep Amethyst
  '#2a9d8f', // Retro Teal
  '#fff3b0', // Popcorn Cream
  '#ffffff'  // Silver Sparkle
];

class ConfettiEngine {
  constructor() {
    this.canvas = null;
    this.ctx = null;
    this.particles = [];
    this.animationFrameId = null;
    this.isRunning = false;
    this.dpr = window.devicePixelRatio || 1;
    this.width = window.innerWidth;
    this.height = window.innerHeight;

    this.onResize = this.onResize.bind(this);
    this.loop = this.loop.bind(this);
  }

  init() {
    if (this.canvas) return;

    this.canvas = document.getElementById('confettiCanvas');
    if (!this.canvas) {
      this.canvas = document.createElement('canvas');
      this.canvas.id = 'confettiCanvas';
      this.canvas.style.position = 'fixed';
      this.canvas.style.top = '0';
      this.canvas.style.left = '0';
      this.canvas.style.width = '100vw';
      this.canvas.style.height = '100vh';
      this.canvas.style.pointerEvents = 'none';
      this.canvas.style.zIndex = '9999';
      document.body.appendChild(this.canvas);
    }

    this.ctx = this.canvas.getContext('2d');
    this.updateSize();
    window.addEventListener('resize', this.onResize);
  }

  updateSize() {
    if (!this.canvas) return;
    this.dpr = window.devicePixelRatio || 1;
    this.width = window.innerWidth;
    this.height = window.innerHeight;
    this.canvas.width = Math.floor(this.width * this.dpr);
    this.canvas.height = Math.floor(this.height * this.dpr);
    if (this.ctx) {
      this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    }
  }

  onResize() {
    this.updateSize();
  }

  createParticle(x, y, vx, vy, shape = 'rect') {
    const color = CINEMA_PALETTE[Math.floor(Math.random() * CINEMA_PALETTE.length)];
    const size = shape === 'circle' ? 4 + Math.random() * 4 : 7 + Math.random() * 6;
    return {
      x,
      y,
      vx,
      vy,
      shape, // 'rect', 'circle', 'star'
      size,
      color,
      tilt: Math.random() * Math.PI * 2,
      tiltSpeed: 0.05 + Math.random() * 0.08,
      rotation: Math.random() * Math.PI * 2,
      rotationSpeed: (Math.random() - 0.5) * 0.2,
      gravity: 0.22 + Math.random() * 0.1,
      drag: 0.982,
      opacity: 1,
      fadeRate: 0.003 + Math.random() * 0.004
    };
  }

  /**
   * Grand 3-Wave Multi-Cannon Podium Celebration
   * Fires left cannon, right cannon, and center starry burst.
   */
  firePodiumCelebration() {
    this.init();

    // Wave 1: Immediate Left & Right Cannons
    this.spawnCannon(0, this.height * 0.95, 60, 16, 26, 120);
    this.spawnCannon(this.width, this.height * 0.95, 120, 16, 26, 120);

    // Wave 2: Center Popcorn & Star Burst after 350ms
    setTimeout(() => {
      if (this.canvas) {
        this.spawnRadialBurst(this.width * 0.5, this.height * 0.45, 90);
      }
    }, 350);

    // Wave 3: Follow-up Cross-Cannons after 900ms
    setTimeout(() => {
      if (this.canvas) {
        this.spawnCannon(0, this.height * 0.9, 50, 14, 22, 90);
        this.spawnCannon(this.width, this.height * 0.9, 130, 14, 22, 90);
      }
    }, 900);

    if (!this.isRunning) {
      this.isRunning = true;
      requestAnimationFrame(this.loop);
    }
  }

  /**
   * Mini celebration on correct guess
   */
  fireMiniSuccess() {
    this.init();
    const centerX = this.width * 0.5;
    const centerY = this.height * 0.35;
    this.spawnRadialBurst(centerX, centerY, 45, 8, 15);

    if (!this.isRunning) {
      this.isRunning = true;
      requestAnimationFrame(this.loop);
    }
  }

  spawnCannon(originX, originY, baseAngleDeg, minSpeed, maxSpeed, count) {
    const baseAngleRad = (baseAngleDeg * Math.PI) / 180;
    const spreadRad = (40 * Math.PI) / 180;

    for (let i = 0; i < count; i++) {
      const angle = baseAngleRad + (Math.random() - 0.5) * spreadRad;
      const speed = minSpeed + Math.random() * (maxSpeed - minSpeed);
      const vx = Math.cos(angle) * speed;
      const vy = -Math.sin(angle) * speed;
      const shape = Math.random() < 0.5 ? 'rect' : Math.random() < 0.8 ? 'circle' : 'star';
      this.particles.push(this.createParticle(originX, originY, vx, vy, shape));
    }
  }

  spawnRadialBurst(originX, originY, count, minSpeed = 5, maxSpeed = 16) {
    for (let i = 0; i < count; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = minSpeed + Math.random() * (maxSpeed - minSpeed);
      const vx = Math.cos(angle) * speed;
      const vy = Math.sin(angle) * speed;
      const shape = Math.random() < 0.4 ? 'rect' : Math.random() < 0.7 ? 'circle' : 'star';
      this.particles.push(this.createParticle(originX, originY, vx, vy, shape));
    }
  }

  loop() {
    if (!this.ctx || !this.isRunning) return;

    this.ctx.clearRect(0, 0, this.width, this.height);

    for (let i = this.particles.length - 1; i >= 0; i--) {
      const p = this.particles[i];

      // Physics update
      p.x += p.vx;
      p.y += p.vy;
      p.vx *= p.drag;
      p.vy *= p.drag;
      p.vy += p.gravity;
      p.tilt += p.tiltSpeed;
      p.rotation += p.rotationSpeed;
      p.opacity -= p.fadeRate;

      // Kill dead particles
      if (p.opacity <= 0 || p.y > this.height + 40) {
        this.particles.splice(i, 1);
        continue;
      }

      // Render particle
      this.ctx.save();
      this.ctx.globalAlpha = Math.max(0, p.opacity);
      this.ctx.fillStyle = p.color;
      this.ctx.translate(p.x, p.y);
      this.ctx.rotate(p.rotation);

      const tiltW = Math.cos(p.tilt);

      if (p.shape === 'rect') {
        this.ctx.fillRect(-p.size / 2, (-p.size * tiltW) / 2, p.size, p.size * tiltW);
      } else if (p.shape === 'circle') {
        this.ctx.beginPath();
        this.ctx.ellipse(0, 0, p.size * 0.5, p.size * 0.5 * Math.abs(tiltW), 0, 0, Math.PI * 2);
        this.ctx.fill();
      } else if (p.shape === 'star') {
        this.drawStar(0, 0, 4, p.size * 0.6, p.size * 0.25);
      }

      this.ctx.restore();
    }

    if (this.particles.length > 0) {
      this.animationFrameId = requestAnimationFrame(this.loop);
    } else {
      this.isRunning = false;
      this.ctx.clearRect(0, 0, this.width, this.height);
    }
  }

  drawStar(cx, cy, spikes, outerRadius, innerRadius) {
    let rot = (Math.PI / 2) * 3;
    let x = cx;
    let y = cy;
    const step = Math.PI / spikes;

    this.ctx.beginPath();
    this.ctx.moveTo(cx, cy - outerRadius);
    for (let i = 0; i < spikes; i++) {
      x = cx + Math.cos(rot) * outerRadius;
      y = cy + Math.sin(rot) * outerRadius;
      this.ctx.lineTo(x, y);
      rot += step;

      x = cx + Math.cos(rot) * innerRadius;
      y = cy + Math.sin(rot) * innerRadius;
      this.ctx.lineTo(x, y);
      rot += step;
    }
    this.ctx.lineTo(cx, cy - outerRadius);
    this.ctx.closePath();
    this.ctx.fill();
  }

  clear() {
    this.particles = [];
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
    this.isRunning = false;
    if (this.ctx) {
      this.ctx.clearRect(0, 0, this.width, this.height);
    }
  }
}

export const confettiEngine = new ConfettiEngine();
export default confettiEngine;
