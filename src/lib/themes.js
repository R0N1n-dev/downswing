// Color themes. Dual: ~80% primary / 20% accent. Triple: 60% dominant / 30% structural / 10% accent.
// Each palette maps onto the shared CSS variables; light/dark variants live in style.css.
export const PALETTES = [
  { id: 'default', name: 'Downswing Blue', group: 'base', primary: '#1E2761', accent: '#3b74ff' },

  // Dual color themes
  { id: 'classic-corporate', name: 'Classic Corporate', group: 'dual', primary: '#1E2761', accent: '#408EC6', note: 'Fintech, SaaS, and crypto' },
  { id: 'energetic-retail', name: 'Energetic Retail', group: 'dual', primary: '#2C3E50', accent: '#E67E22', note: 'E-commerce, delivery, and travel' },
  { id: 'eco-organic', name: 'Eco-Organic', group: 'dual', primary: '#2C5F2D', accent: '#97BC62', note: 'Sustainability, gardening, and wellness' },
  { id: 'cyber-contrast', name: 'Cyber High-Contrast', group: 'dual', primary: '#1A1A1A', accent: '#C1DD13', note: 'Productivity, dev tools, and fitness' },
  { id: 'minimalist-editorial', name: 'Minimalist Editorial', group: 'dual', primary: '#CC313D', accent: '#F7C5CC', note: 'Fashion, portfolio, and lifestyle' },

  // Triple color themes
  { id: 'high-end-trio', name: 'The High-End Trio', group: 'triple', primary: '#1E2761', secondary: '#408EC6', accent: '#7A2048', note: 'Luxury retail, portfolios, fine dining' },
  { id: 'bright-tech', name: 'The Bright Tech Vibe', group: 'triple', primary: '#2D3047', secondary: '#2EC4B6', accent: '#FF7A59', note: 'SaaS dashboards and data tracking' },
  { id: 'playful-pastel', name: 'The Playful Pastel', group: 'triple', primary: '#B298E7', secondary: '#B8E3E9', accent: '#F5B8D5', note: 'Social, mindfulness, and education' },
  { id: 'earthy-muted', name: 'The Earthy Muted', group: 'triple', primary: '#4A5842', secondary: '#EFEBE4', accent: '#D98A6C', note: 'Home decor, plants, and architecture' },

  // Pastel color themes
  { id: 'pastel-mint', name: 'Pastel Mint', group: 'pastel', primary: '#62B8A4', secondary: '#B2E5D8', accent: '#8FE0C2', note: 'Calm, wellness, and mindfulness' },
  { id: 'pastel-peach', name: 'Pastel Peach', group: 'pastel', primary: '#E8A88C', secondary: '#F6D6C3', accent: '#FFC9A5', note: 'Warm lifestyle, food, and beauty' },
  { id: 'pastel-sky', name: 'Pastel Sky', group: 'pastel', primary: '#86B4E6', secondary: '#C2DBF5', accent: '#A5CDF2', note: 'Airy productivity and journals' },
]

export const PALETTE_IDS = PALETTES.map((p) => p.id)

export const paletteById = (id) => PALETTES.find((p) => p.id === id) || PALETTES[0]
