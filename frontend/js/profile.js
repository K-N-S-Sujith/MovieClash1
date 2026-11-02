/**
 * MovieClash — User Profile & Identity Manager
 * Manages standard persistent user ID and default / customizable player nickname.
 */

const PROFILE_KEY = 'movieclash_user_profile';

const COOL_ADJECTIVES = [
  'Super', 'Mega', 'Hyper', 'Swift', 'Lucky', 'Epic', 'Brave', 'Cosmic',
  'Master', 'Prime', 'Grand', 'Silent', 'Mighty', 'Golden', 'Star', 'Vivid'
];

const MOVIE_NOUNS = [
  'Cinephile', 'Director', 'Critic', 'Hero', 'Actor', 'Screenplay',
  'BoxOffice', 'Clapper', 'Cinematics', 'CinemaBuff', 'MovieLover', 'FilmFan'
];

function generateDefaultName() {
  const adj = COOL_ADJECTIVES[Math.floor(Math.random() * COOL_ADJECTIVES.length)];
  const noun = MOVIE_NOUNS[Math.floor(Math.random() * MOVIE_NOUNS.length)];
  const num = Math.floor(100 + Math.random() * 900);
  return `${adj}${noun}${num}`;
}

function generateStandardUserId() {
  return 'usr_' + Math.random().toString(36).substring(2, 9) + Date.now().toString(36).slice(-4);
}

export class UserProfileManager {
  constructor() {
    this.profile = this.loadProfile();
  }

  loadProfile() {
    try {
      const raw = localStorage.getItem(PROFILE_KEY);
      if (raw) {
        const data = JSON.parse(raw);
        if (data && data.userId && data.name) {
          return data;
        }
      }
    } catch (e) {
      console.warn('Failed to parse stored profile, generating new one:', e);
    }

    // Initialize fresh profile
    const fresh = {
      userId: generateStandardUserId(),
      name: generateDefaultName(),
      created: Date.now()
    };
    this.save(fresh);
    return fresh;
  }

  save(data) {
    try {
      localStorage.setItem(PROFILE_KEY, JSON.stringify(data));
      this.profile = data;
    } catch (e) {
      console.error('Failed to save profile to localStorage:', e);
    }
  }

  getProfile() {
    return this.profile;
  }

  getUserId() {
    return this.profile.userId;
  }

  getName() {
    return this.profile.name;
  }

  setName(newName) {
    if (!newName || !newName.trim()) return this.profile.name;
    const sanitized = newName.trim().slice(0, 18);
    this.profile.name = sanitized;
    this.save(this.profile);
    return sanitized;
  }
}

export const userProfile = new UserProfileManager();
export default userProfile;

