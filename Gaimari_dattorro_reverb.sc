/*
Attribution:

[1] Soundfile

// Downloaded on April 3rd, 2014
// P: Violin: Single notes Pizzicato G4-G5 by Carlos_Vaquero
// http://www.freesound.org/people/Carlos_Vaquero/packs/9513/
// License: Attribution Noncommercial
*/

// PlayBuf, CtkBuffer and Slapback Echo - mono!
// Using Private Bussing
(
var score, playBufSynthDef, reverbSynthDef, feedbackSynthDef;
var buffer, start, dur;
var buffersD, buffersC, sndPath, allSounds;
var dafxBus1, dafxBus2, busA, busB, group;
var sinCosPanLaw, equalPowMix, sampConvert, allPoleCoeffFunc, combT60Func, sampsToTime;

// filter functions


// vars for NRT output
var outputPath, headerFormat, sampleFormat, numOutputChannels, sampleRate;

/////////////// SET VARIABLE VALUES ///////////////

// set the NRT vars here...
outputPath = "Gaimari_assignment_4_part2_etude.wav".resolveRelative; // output file path
headerFormat = "WAV";                 // soundfile header format
sampleFormat = "int24";               // soundfile sample format
sampleRate = 44100;                   // sample rate
numOutputChannels = 2;                // stereo --> 2 channels

// create a score
score = CtkScore.new;

// create the sendBus
dafxBus1 = CtkAudio.new(2); // stereo channel
dafxBus2 = CtkAudio.new(2); // stereo channel
busA = CtkAudio.new(2); // bus for reverb sigA out
busB = CtkAudio.new(2); // bus for reverb sigB out
// ... which is what we'll be sending!
// create a node group
group = CtkGroup.new;

///////////////    DEFINE FILTER FUNCTIONS    ///////////////

/////////////// DEFINE SYNTH HELPER FUNCTIONS ///////////////

// sine-cosine panning law coefficient function
// angle argument in degrees
sinCosPanLaw = { arg angleInDegrees = 0;
	var angleInRadians;
	var theta;

	angleInRadians = angleInDegrees/180*pi;

	theta = pi/4 - angleInRadians;

	[theta.cos, theta.sin]
};

equalPowMix= { arg mix = 0.5;

	[(1 - mix).sqrt, mix.sqrt]
};

// converts from Dattoro Sample Rate of 29761Hz to this sampleRate
sampConvert = {arg num;
	(num / 29761) * sampleRate
};

allPoleCoeffFunc = { arg delayTime, decayTime;

	var gFac;

	gFac = 10.pow(-3 * delayTime / decayTime);

	// return
	gFac;
};

combT60Func = { arg delay, gFac;

	var t60;

	t60 = gFac.sign * (-3 * delay / log10(gFac.abs));

	// return
	t60;
};

sampsToTime = { arg numSamples, sampleRate;

	var time;

	time = numSamples / sampleRate;

	// return
	time;
};

///////////////// DEFINE SYNTHS //////////////////

playBufSynthDef = CtkSynthDef.new(\myStereoPlayBufSynth, {arg dur, gain = 0.0, ampEnv = 0.0, panAngle = 0.0, rate = 1, loop = 0, buffer = 0, sendGain = 0.0, sendBus = 0.0;
	var numChannels = 2; // stereo buffer
	var amp, sendAmp;          // a few vars for synthesis
	var sig;     // vars assigned to audio signals
	var directOut, sendOut;

	// calcs
	amp = gain.dbamp;  // convert from gain in dB to linear amplitude scale
	sendAmp = sendGain.dbamp;

	// read-in signal & envelope
	sig = PlayBuf.ar(numChannels, buffer,  BufRateScale.kr(buffer) * rate, loop: loop);
	sig = ampEnv * sig;

	// assign direct and send out
	// directOut = amp * sinCosPanLaw.value(panAngle) * sig;  // pan on the way out (make it stereo);
	sendOut = sendAmp * sig;  // effects send gain (mono signal)

	// outputs here
	/*Out.ar(
		bus, // main out
		directOut
	);*/
	Out.ar(
		sendBus, // effects send out
		sendOut
	);
});

feedbackSynthDef = CtkSynthDef.new(\feedbackSynth, {arg dur, gain = 0.0, ris = 0.01, dec = 0.01, mix = 1.0, dirPanAngle = 0.0, delPanAngle = 0.0, delayTime = 0.2, maxDelayTime = 0.2, decayTime = 0.5, receiveBus, sendBus = 0;
    var numChannels = 2; // mono bus!
    var amp, dirAmp, delAmp;          // a few vars for synthesis
    var direct, out;     // vars assigned to audio signals
    var delay;
    var ampEnv;       // var for envelope signal

    // calcs
    amp = gain.dbamp;  // overall scalar
    #dirAmp, delAmp = equalPowMix.value(mix);  // direct & delay scalar

    // the amplitude envelope nested in the UGen that synthesises the envelope
    ampEnv = EnvGen.kr(
        Env.linen(ris, 1.0 - (ris + dec), dec),
        timeScale: dur
    );

    // read sound in
    direct = In.ar(receiveBus, numChannels);

    // delay
    // NOTE: we could have added the direct signal here
    //       but have kept separate so the direct and delayed
    //       signal can be panned separately
    delay = CombN.ar(direct, maxDelayTime, delayTime, decayTime);

    // add panned direct and delay to out & envelope
    out = amp * ampEnv * (
    (sinCosPanLaw.value(dirPanAngle) * dirAmp * direct) +
        (sinCosPanLaw.value(delPanAngle) * delAmp * delay)
    );

    // out!!
    Out.ar(sendBus, out)
});


reverbSynthDef = CtkSynthDef.new(\reverbSynth, {arg dur, gain = 0.0, ris = 0.01, dec = 0.01, mix = 1.0, dirPanAngle = 0.0, revPanAngle = 0.0, preDelayTime = 0.2, maxDelayTime = 0.2, lowCut = 200.0, receiveBus, sendBus = 0.0;
	var numChannels = 2; // stereo bus!
	var amp, dirAmp, revAmp;          // a few vars for synthesis
	var direct, out;     // vars assigned to audio signals
	var ampEnv;       // var for envelope signal
	var del, time; // temp vars

	// variables for local/private bussing
	var sigA, sigB;
	var reverb;

	// variables for output taps
	var yL = 0;
	var yR = 0;
	var l48, l54, l59, l63, l30, l33, l39;
	var r24, r30, r33, r39, r54, r59, r63;

	// variables for reverberation
	var sampR = 29761;
	var excurs = 8; // half of original value for simpler calcs using LFNoise2
	var revDecay = 0.5;
	var bandwidth = 0.9995; // QUESTION: what is this parameter's purpose?
	var damping = 0.0005; // QUESTION: what is this parameter's purpose?
	var sapCoeffs = [142, 107, 379, 277];
	var inputDiff = [0.75, 0.75, 0.5, 0.5];
	var sigAParams = [[(908 + excurs) + (LFNoise2.kr(1) * excurs), 0.70, 4217, 1 - damping, lowCut, revDecay], [2656, 0.50, 3163, 1, sampleRate / 2, revDecay]]; // params from Dattorro ugen graph, some excluded
	var sigBParams = [[(672 + excurs) + (LFNoise2.kr(1) * excurs), 0.70, 4453, 1 - damping, lowCut, revDecay], [1800, 0.50, 3720, 1, sampleRate / 2, revDecay]]; // params from Dattorro ugen graph, some excluded

	// calcs
	amp = gain.dbamp;  // overall scalar
	#dirAmp, revAmp = equalPowMix.value(mix);  // direct & delay scalar

	// the amplitude envelope nested in the UGen that synthesises the envelope
	ampEnv = EnvGen.kr(
		Env.linen(ris, 1.0 - (ris + dec), dec),
		timeScale: dur
	);

	// read sound in
	direct = In.ar(receiveBus, numChannels);

	// multiply signal
	reverb = direct * 0.5;

	// apply delay (delayTime)
	reverb = DelayC.ar(in: reverb, maxdelaytime: maxDelayTime, delaytime: preDelayTime, mul: 1, add: 0);

	// apply lowpass filter (lowCut)
	reverb = LPF.ar(in: reverb, freq: lowCut, mul: 1, add: 0);

	// loop for SAP1, SAP2, (revDecay & input diff1 = 0.750) SAP3, SAP4 (revDecay & input diff2 = 0.625)
	sapCoeffs.do({arg c, i;
		var d = sampsToTime.value(sapCoeffs.at(i), sampR);
		var t = combT60Func.value(d, inputDiff.at(i));
		reverb = AllpassC.ar(reverb, maxDelayTime, d, t);
	});


	// SIG A: add SigB, SAP5 (w/ excursion), delay, LPF, decay multiplier, SAP6, delay, decay multiplier
	sigA = reverb + In.ar(busB, numChannels);

	del = sampsToTime.value(sigAParams.at(0).at(0), sampR);
	time = combT60Func.value(del, sigAParams.at(0).at(1));
	sigA = AllpassC.ar(sigA, maxDelayTime, del, time); // all pass
	l48 = sigA; // LEFT TAP
	sigA = DelayC.ar(sigA, maxDelayTime, sampsToTime.value(sigAParams.at(0).at(2), sampR)); // delay
	l54 = sigA; // LEFT TAP
	r54 = sigA; // RIGHT TAP
	sigA = LPF.ar(in: sigA, freq: sigAParams.at(0).at(4), mul: 1, add: 0);
	sigA = sigA * sigAParams.at(0).at(5);

	del = sampsToTime.value(sigAParams.at(1).at(0), sampR);
	time = combT60Func.value(del, sigAParams.at(1).at(1));
	sigA = AllpassC.ar(sigA, maxDelayTime, del, time); // all pass
	l59 = sigA; // LEFT TAP
	r59 = sigA; // RIGHT TAP
	sigA = DelayC.ar(sigA, maxDelayTime, sampsToTime.value(sigAParams.at(1).at(2), sampR)); // delay
	l63 = sigA; // LEFT TAP
	r63 = sigA; // RIGHT TAP
	// sigA = LPF.ar(in: sigA, freq: sigAParams.at(1).at(4), mul: 1, add: 0);
	sigA = sigA * sigAParams.at(1).at(5);

	// SIG B: add SigA, SAP7 (w/ excursion), delay, LPF, decay multiplier, SAP8, delay, decay multiplier
	sigB = reverb + In.ar(busA, numChannels);

	del = sampsToTime.value(sigBParams.at(0).at(0), sampR);
	time = combT60Func.value(del, sigBParams.at(0).at(1));
	sigB = AllpassC.ar(sigB, maxDelayTime, del, time); // all pass
	r24 = sigB; // RIGHT TAP
	sigB = DelayC.ar(sigB, maxDelayTime, sampsToTime.value(sigBParams.at(0).at(2), sampR)); // delay
	l30 = sigB; // LEFT TAP
	r30 = sigB; // RIGHT TAP
	sigB = LPF.ar(in: sigB, freq: sigBParams.at(0).at(4), mul: 1, add: 0);
	sigB = sigB * sigBParams.at(0).at(5);

	del = sampsToTime.value(sigBParams.at(1).at(0), sampR);
	time = combT60Func.value(del, sigBParams.at(1).at(1));
	sigB = AllpassC.ar(sigB, maxDelayTime, del, time); // all pass
	l33 = sigB; // LEFT TAP
	r33 = sigB; // RIGHT TAP
	sigB = DelayC.ar(sigB, maxDelayTime, sampsToTime.value(sigBParams.at(1).at(2), sampR)); // delay
	l39 = sigB; // LEFT TAP
	r39 = sigB; // RIGHT TAP
	// sigA = LPF.ar(in: sigB, freq: sigBParams.at(1).at(4), mul: 1, add: 0);
	sigB = sigB * sigAParams.at(1).at(5);

	// Feedback sigA and sigB

	Out.ar(
		busA,
		sigA
	);

	Out.ar(
		busB,
		sigB
	);

	yL = (0.6)*(l48  + l54 - l59 + l63 - l30 - l33 - l39);
	yR = (0.6)*(r39  + r30 - r24 + r39 - r54 - r59 - r63);

	// add panned direct and delay to out & envelope
	out = amp * ampEnv * (
		(sinCosPanLaw.value(dirPanAngle) * dirAmp * direct) +
		(sinCosPanLaw.value(revPanAngle) * revAmp * [yL, yR])
	);

	// out!!
	Out.ar(sendBus, out)
});


///////////////// CREATE BUFFERS //////////////////

// get path of sampled sounds folder
sndPath = "continious/".resolveRelative;
// get individual file paths
allSounds = (sndPath ++ "*").pathMatch;

// collects Ctk buffers into a dictionary with keys of filenames
buffersC = Dictionary.new;
allSounds.do{arg bufferPath;
	var name;
	bufferPath = bufferPath.replace("\\", "/"); // converts windows path to unix path
	name = bufferPath.basename.splitext.at(0); // gets just the file name
	buffersC.add((name) -> (CtkBuffer.new(bufferPath)));
	score.add(buffersC.at(name));
};


// get path of sampled sounds folder
sndPath = "discrete/".resolveRelative;
// get individual file paths
allSounds = (sndPath ++ "*").pathMatch;

// collects Ctk buffers into a dictionary with keys of filenames
buffersD = Dictionary.new;
allSounds.do{arg bufferPath;
	var name;
	bufferPath = bufferPath.replace("\\", "/"); // converts windows path to unix path
	name = bufferPath.basename.splitext.at(0); // gets just the file name
	buffersD.add((name) -> (CtkBuffer.new(bufferPath)));
	score.add(buffersD.at(name));
};

///////////////// POPULATE THE SCORE //////////////////

// add the buffer and node group to the score
// NOTE: buffers & node groups must be added to the score for the CtkSynthDef to access!
score.add(group);


// define the notes / add to score
// a single note - original pitch
buffer = buffersD.at("vocal_talking_motion_1");
start = 0.1;
dur = buffer.duration * 1.5;
score.add(
	playBufSynthDef.note(starttime: start, duration: dur, addAction: \head, target: group)
	.dur_(dur)
	.gain_(0.0)
	.sendGain_(12.0)
	.ampEnv_(1.0)
	.panAngle_(0)
	.rate_(CtkControl.env(Env([1, 0.3, 1, 1, 0.3, 1], [0.2, 0.2, 0.2, 0.2, 0.2], \sin), start, timeScale: dur))
	.buffer_(buffer)
	.sendBus_(dafxBus1)
);

score.add(
	feedbackSynthDef.note(starttime: start, duration: dur, addAction: \tail, target: group)
	.dur_(dur)
	.gain_(12.0)
	.ris_(0.0)
	.dec_(0.0)
	.mix_(0.5)
	.dirPanAngle_(0.0)
	.delPanAngle_(0.0)
	.delayTime_(CtkControl.env(Env([1, 0.5, 0.1, 0.01, 0.1, 0.5, 1], [0.1, 0.1, 0.3, 0.3, 0.1, 0.1], \sin), start, timeScale: dur, levelScale: 0.4))
	.maxDelayTime_(0.5)
	.decayTime_(CtkControl.env(Env([1, 0.8, 0.5], [0.4, 0.3, 0.3], \lin), start, timeScale: dur, levelScale: 0.4))
	.receiveBus_(dafxBus1)
	.sendBus_(dafxBus2)
);

score.add(
	reverbSynthDef.note(starttime: start, duration: dur, addAction: \tail, target: group)
	.dur_(dur)
	.gain_()
	.dirPanAngle_(0.0)
	.revPanAngle_(0.0)
	.preDelayTime_(0.1)
	.maxDelayTime_(CtkControl.env(Env([0.1, 0.2, 0.3, 0.4, 0.5], [0.25, 0.25, 0.25, 0.25], \lin), start, timeScale: dur, levelScale: 1))
	.lowCut_(CtkControl.env(Env([0.1, 0.2, 0.3, 0.4, 1.0], [0.1, 0.2, 0.3, 0.4], 6), start, timeScale: dur, levelScale: 800))
	.mix_(0.5)
	.receiveBus_(dafxBus2)
	.sendBus_(0)
);

///////////////// RENDER THE SCORE //////////////////
// write score to sound file with the -write message
// NOTE: we're using argument names to specify the args. For 'duration', we're letting Ctk
//       do the work for us!
score.write(
	path: outputPath.standardizePath,
	sampleRate: sampleRate,
	headerFormat: headerFormat,
	sampleFormat: sampleFormat,
	options: ServerOptions.new.numOutputBusChannels_(numOutputChannels)
);

// free bus to "make nice"
dafxBus1.free;
dafxBus2.free;
busA.free;
busB.free;
)
SFPlayer("Gaimari_assignment_4_part2_etude.wav".resolveRelative).gui;