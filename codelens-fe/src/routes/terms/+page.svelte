<script lang="ts">
	import { goto } from '$app/navigation';
</script>

<svelte:head>
	<title>Terms of Service - CodeLens</title>
</svelte:head>

<div class="min-h-screen bg-gray-50">
	<!-- Header -->
	<header class="bg-white border-b border-gray-200">
		<div class="max-w-4xl mx-auto px-4 py-4 flex items-center gap-4">
			<button onclick={() => goto('/')} class="text-gray-500 hover:text-gray-700">
				<svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
				</svg>
			</button>
			<div class="flex items-center gap-3">
				<img src="/codelens.png" alt="CodeLens" class="w-8 h-8" />
				<span class="font-semibold text-gray-900">CodeLens</span>
			</div>
		</div>
	</header>

	<!-- Content -->
	<main class="max-w-4xl mx-auto px-4 py-8">
		<div class="bg-white rounded-lg shadow-sm border border-gray-200 p-8">
			<h1 class="text-3xl font-bold text-gray-900 mb-2">Terms of Service</h1>
			<p class="text-sm text-gray-500 mb-8">Last updated: January 2, 2026</p>

			<div class="prose prose-gray max-w-none">
				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">1. Acceptance of Terms</h2>
				<p class="text-gray-600 mb-4">
					By accessing or using CodeLens ("Service"), you agree to be bound by these Terms of Service ("Terms"). If you do not agree to these Terms, do not use the Service. These Terms constitute a legally binding agreement between you and CodeLens.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">2. Description of Service</h2>
				<p class="text-gray-600 mb-4">
					CodeLens is an AI-powered code review platform that:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Analyzes pull requests and merge requests from GitHub and GitLab</li>
					<li>Provides automated code review using multiple AI providers</li>
					<li>Performs static analysis and security scanning</li>
					<li>Posts review comments to your repositories</li>
					<li>Provides analytics and insights on code quality</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">3. Account Registration</h2>
				<p class="text-gray-600 mb-4">
					To use the Service, you must:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Sign in using a valid Google account</li>
					<li>Provide accurate and complete information</li>
					<li>Maintain the security of your account credentials</li>
					<li>Be at least 16 years of age</li>
				</ul>
				<p class="text-gray-600 mb-4">
					You are responsible for all activities that occur under your account.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">4. Acceptable Use</h2>
				<p class="text-gray-600 mb-4">You agree not to:</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Use the Service for any unlawful purpose</li>
					<li>Submit malicious code, malware, or security exploits for review</li>
					<li>Attempt to gain unauthorized access to the Service or related systems</li>
					<li>Interfere with or disrupt the Service or servers</li>
					<li>Reverse engineer, decompile, or disassemble the Service</li>
					<li>Use the Service to infringe intellectual property rights</li>
					<li>Submit code that you do not have rights to</li>
					<li>Exceed rate limits or abuse API access</li>
					<li>Share your account credentials with third parties</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">5. Code and Data</h2>

				<h3 class="text-lg font-medium text-gray-800 mt-6 mb-3">5.1 Your Code</h3>
				<p class="text-gray-600 mb-4">
					You retain all ownership rights to your code. By submitting code for review, you grant us a limited, non-exclusive license to process your code for the purpose of providing the Service.
				</p>

				<h3 class="text-lg font-medium text-gray-800 mt-6 mb-3">5.2 AI Processing</h3>
				<p class="text-gray-600 mb-4">
					Your code may be sent to third-party AI providers (including but not limited to ZhipuAI, Anthropic, Google, OpenAI) for analysis. We do not use your code to train AI models. Please review our Privacy Policy for details on data handling.
				</p>

				<h3 class="text-lg font-medium text-gray-800 mt-6 mb-3">5.3 Access Tokens</h3>
				<p class="text-gray-600 mb-4">
					You are responsible for providing valid GitHub/GitLab access tokens with appropriate permissions. Tokens are stored securely and used solely for accessing your repositories and posting review comments.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">6. Service Availability</h2>
				<p class="text-gray-600 mb-4">
					We strive to maintain high availability but do not guarantee uninterrupted access. The Service may be temporarily unavailable for maintenance, updates, or circumstances beyond our control. We reserve the right to modify, suspend, or discontinue the Service at any time.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">7. AI-Generated Content</h2>
				<p class="text-gray-600 mb-4">
					Review comments and suggestions are generated by AI and should be treated as advisory only. You are responsible for:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Reviewing and validating all AI-generated suggestions before implementation</li>
					<li>Making final decisions about code changes</li>
					<li>Ensuring code quality and security in your projects</li>
				</ul>
				<p class="text-gray-600 mb-4">
					<strong>The Service does not replace human code review and should be used as a supplementary tool.</strong>
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">8. Intellectual Property</h2>
				<p class="text-gray-600 mb-4">
					The Service, including its design, features, and content (excluding your code), is owned by CodeLens and protected by intellectual property laws. You may not copy, modify, distribute, or create derivative works without our permission.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">9. Third-Party Services</h2>
				<p class="text-gray-600 mb-4">
					The Service integrates with third-party services including:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Google (authentication)</li>
					<li>GitHub and GitLab (repository access)</li>
					<li>AI providers (code analysis)</li>
				</ul>
				<p class="text-gray-600 mb-4">
					Your use of these services is subject to their respective terms and privacy policies.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">10. Limitation of Liability</h2>
				<p class="text-gray-600 mb-4">
					TO THE MAXIMUM EXTENT PERMITTED BY LAW:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>THE SERVICE IS PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND</li>
					<li>WE DISCLAIM ALL WARRANTIES, EXPRESS OR IMPLIED, INCLUDING MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE</li>
					<li>WE ARE NOT LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES</li>
					<li>OUR TOTAL LIABILITY SHALL NOT EXCEED THE AMOUNT PAID BY YOU IN THE 12 MONTHS PRECEDING THE CLAIM</li>
					<li>WE ARE NOT RESPONSIBLE FOR SECURITY VULNERABILITIES NOT DETECTED BY THE SERVICE</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">11. Indemnification</h2>
				<p class="text-gray-600 mb-4">
					You agree to indemnify and hold harmless CodeLens and its officers, directors, employees, and agents from any claims, damages, losses, or expenses arising from your use of the Service, violation of these Terms, or infringement of any third-party rights.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">12. Termination</h2>
				<p class="text-gray-600 mb-4">
					We may suspend or terminate your access to the Service at any time for violation of these Terms or for any other reason. You may terminate your account at any time by contacting us. Upon termination:
				</p>
				<ul class="list-disc list-inside text-gray-600 mb-4 space-y-2">
					<li>Your access to the Service will be revoked</li>
					<li>Your data will be handled according to our Privacy Policy</li>
					<li>Provisions that should survive termination will remain in effect</li>
				</ul>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">13. Modifications to Terms</h2>
				<p class="text-gray-600 mb-4">
					We reserve the right to modify these Terms at any time. Material changes will be communicated by updating the "Last updated" date and, for significant changes, through email notification. Continued use of the Service after changes constitutes acceptance.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">14. Governing Law</h2>
				<p class="text-gray-600 mb-4">
					These Terms are governed by and construed in accordance with applicable laws. Any disputes shall be resolved through binding arbitration or in the courts of competent jurisdiction, as determined by applicable law.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">15. Severability</h2>
				<p class="text-gray-600 mb-4">
					If any provision of these Terms is found unenforceable, the remaining provisions will continue in effect. The unenforceable provision will be modified to the minimum extent necessary to make it enforceable.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">16. Entire Agreement</h2>
				<p class="text-gray-600 mb-4">
					These Terms, together with our Privacy Policy, constitute the entire agreement between you and CodeLens regarding the Service and supersede any prior agreements.
				</p>

				<h2 class="text-xl font-semibold text-gray-900 mt-8 mb-4">17. Contact</h2>
				<p class="text-gray-600 mb-4">
					For questions about these Terms:
				</p>
				<ul class="list-none text-gray-600 mb-4 space-y-1">
					<li>Email: <a href="mailto:legal@example.com" class="text-primary-600 hover:underline">legal@example.com</a></li>
				</ul>
			</div>
		</div>

		<!-- Footer Links -->
		<div class="mt-8 text-center text-sm text-gray-500">
			<a href="/privacy" class="hover:text-primary-600">Privacy Policy</a>
			<span class="mx-2">·</span>
			<a href="/" class="hover:text-primary-600">Back to Login</a>
		</div>
	</main>
</div>
