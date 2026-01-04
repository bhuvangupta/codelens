/** @type {import('tailwindcss').Config} */
export default {
	content: ['./src/**/*.{html,js,svelte,ts}'],
	theme: {
		extend: {
			colors: {
				primary: {
					DEFAULT: '#451a03',
					50: '#fdf8f6',
					100: '#f2e8e5',
					200: '#eaddd7',
					300: '#e0cec7',
					400: '#d2bab0',
					500: '#bfa094',
					600: '#a18072',
					700: '#451a03',
					800: '#3d1803',
					900: '#2d1202'
				},
				secondary: '#5c2a0a',
				accent: {
					DEFAULT: '#78350f',
					light: '#92400e',
					dark: '#451a03'
				},
				dark: '#1c1917',
				surface: '#292524'
			},
			fontFamily: {
				sans: ['Inter', 'system-ui', 'sans-serif'],
				mono: ['JetBrains Mono', 'Consolas', 'monospace']
			}
		}
	},
	plugins: [
		require('@tailwindcss/typography')
	]
};
