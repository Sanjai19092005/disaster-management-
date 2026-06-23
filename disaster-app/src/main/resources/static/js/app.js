document.addEventListener('DOMContentLoaded', () => {
  // Navigation
  const navItems = document.querySelectorAll('.nav-item');
  const sections = document.querySelectorAll('.view-section');

  navItems.forEach(item => {
    item.addEventListener('click', () => {
      const target = item.getAttribute('data-target');
      
      // Active Nav
      navItems.forEach(i => i.classList.remove('active'));
      item.classList.add('active');

      // Active Section
      sections.forEach(s => s.classList.remove('active'));
      const activeSection = document.getElementById(target);
      activeSection.classList.add('active');

      // Graph initialization if view is selected
      if (target === 'knowledge-graph') {
        initOrUpdateGraph();
      }
    });
  });

  // Global State
  let cy = null;
  let resourceChart = null;

  // SPARQL Playground Queries
  const queries = {
    'all-triples': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?subject ?predicate ?object
WHERE {
  ?subject ?predicate ?object .
}
LIMIT 50`,

    'find-shelters': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?shelter ?capacity ?location
WHERE {
  ?shelter rdf:type ?shelterType .
  ?shelterType rdfs:subClassOf* :Shelter .
  OPTIONAL { ?shelter :capacity ?capacity } .
  OPTIONAL { ?shelter :locatedAt ?location } .
}`,

    'find-hospitals': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?hospital ?capacity
WHERE {
  ?hospital rdf:type :Hospital .
  ?hospital :locatedAt :Mylapore .
  OPTIONAL { ?hospital :capacity ?capacity }
}`,

    'find-resources': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?requiredResource
WHERE {
  :ChennaiFlood2026 :requiresResource ?requiredResource .
}`,

    'find-agencies': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?agency ?resourceProvided ?agencyLocation
WHERE {
  ?agency rdf:type ?agencyType .
  ?agencyType rdfs:subClassOf* :Agency .
  ?agency :providesResource ?resourceProvided .
  OPTIONAL { ?agency :locatedAt ?agencyLocation }
}`,

    'find-roads': `PREFIX : <http://disaster.org/ontology#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?connection ?loc1 ?loc2 ?distance ?status
WHERE {
  ?connection :connectedTo ?loc1 .
  ?connection :connectedTo ?loc2 .
  FILTER(?loc1 != ?loc2) .
  OPTIONAL { ?connection :distance ?distance } .
  OPTIONAL { ?connection :status ?status } .
}`
  };

  // Initial loads
  try { checkStatus(); } catch (e) { console.error("Status check failed: ", e); }
  try { loadDashboardData(); } catch (e) { console.error("Dashboard data load failed: ", e); }
  try { initSparqlPlayground(); } catch (e) { console.error("SPARQL playground init failed: ", e); }
  try { initOpsRoom(); } catch (e) { console.error("Ops Room init failed: ", e); }
  try { initPredictiveWeather(); } catch (e) { console.error("Predictive weather init failed: ", e); }

  // Status Check (Backend & Fuseki)
  function checkStatus() {
    // Check backend
    fetch('/api/analytics')
      .then(res => {
        if (res.ok) {
          document.getElementById('backend-status-dot').className = 'status-dot online';
          document.getElementById('backend-status-text').innerText = 'Online';
          document.getElementById('fuseki-status-dot').className = 'status-dot online';
          document.getElementById('fuseki-status-text').innerText = 'Connected';
          
          return res.json();
        } else {
          throw new Error();
        }
      })
      .then(data => {
        // Also fetch triple count by query
        fetchTripleCount();
      })
      .catch(() => {
        document.getElementById('backend-status-dot').className = 'status-dot offline';
        document.getElementById('backend-status-text').innerText = 'Offline';
        document.getElementById('fuseki-status-dot').className = 'status-dot offline';
        document.getElementById('fuseki-status-text').innerText = 'Error';
      });
  }

  function fetchTripleCount() {
    const query = 'SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }';
    fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query })
    })
    .then(res => res.json())
    .then(data => {
      if (data && data.length > 0) {
        document.getElementById('triple-count').innerText = data[0].count;
      }
    })
    .catch(err => console.error('Error fetching triple count:', err));
  }

  // Dashboard Analytics
  function loadDashboardData() {
    fetch('/api/analytics')
      .then(res => res.json())
      .then(data => {
        document.getElementById('stat-disasters').innerText = data.disasterCount || 0;
        document.getElementById('stat-shelters').innerText = data.shelterCount || 0;
        document.getElementById('stat-capacity').innerText = data.shelterCapacity || 0;
        document.getElementById('stat-agencies').innerText = data.agencyCount || 0;

        // Draw Resource Distribution Chart
        drawResourceChart(data.resourceDistribution || {});
      })
      .catch(err => console.error('Error loading dashboard analytics:', err));
  }

  function drawResourceChart(distribution) {
    try {
      const ctx = document.getElementById('resourceChart').getContext('2d');
      
      // Destroy previous instance
      if (resourceChart) {
        resourceChart.destroy();
      }

      const labels = Object.keys(distribution);
      const counts = Object.values(distribution);

      // Custom CSS HSL tailored colors
      const colors = [
        'rgba(0, 210, 255, 0.65)',  // cyan
        'rgba(192, 132, 252, 0.65)', // purple
        'rgba(0, 255, 135, 0.65)',   // success green
        'rgba(255, 208, 0, 0.65)'    // warning yellow
      ];
      const borderColors = [
        '#00d2ff',
        '#c084fc',
        '#00ff87',
        '#ffd000'
      ];

      resourceChart = new Chart(ctx, {
        type: 'bar',
        data: {
          labels: labels.map(l => l.replace(/([A-Z])/g, ' $1').trim()),
          datasets: [{
            label: 'Available Resource Providers',
            data: counts,
            backgroundColor: labels.map((_, i) => colors[i % colors.length]),
            borderColor: labels.map((_, i) => borderColors[i % borderColors.length]),
            borderWidth: 1.5,
            borderRadius: 6
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              labels: {
                color: '#94a3b8',
                font: { family: 'Outfit' }
              }
            }
          },
          scales: {
            x: {
              grid: { color: 'rgba(255, 255, 255, 0.05)' },
              ticks: { color: '#94a3b8', font: { family: 'Outfit' } }
            },
            y: {
              grid: { color: 'rgba(255, 255, 255, 0.05)' },
              ticks: { 
                color: '#94a3b8', 
                font: { family: 'Outfit' },
                stepSize: 1
              }
            }
          }
        }
      });
    } catch (e) {
      console.error("Failed to render Chart.js: ", e);
    }
  }

  // Cytoscape Knowledge Graph Visualizer
  function initOrUpdateGraph() {
    fetch('/api/graph')
      .then(res => res.json())
      .then(graphData => {
        renderCytoscape(graphData);
      })
      .catch(err => console.error('Error fetching graph data:', err));
  }

  function renderCytoscape(graphData) {
    const elements = [];
    
    // Map nodes
    graphData.nodes.forEach(node => {
      const type = node.type || 'Unknown';
      let color = '#64748b'; // default slate
      
      if (type === 'Disaster' || type === 'Flood' || type === 'Earthquake' || type === 'Cyclone') color = '#ff005b'; // Red
      else if (type === 'Location' || type === 'City' || type === 'District' || type === 'State') color = '#00ff87'; // Green
      else if (type === 'Agency' || type === 'GovernmentAgency' || type === 'NGO' || type === 'RescueTeam') color = '#0072ff'; // Blue
      else if (type === 'Resource' || type === 'Food' || type === 'Water' || type === 'Medicine') color = '#ffd000'; // Yellow
      else if (type === 'Shelter' || type === 'Hospital' || type === 'ReliefCenter') color = '#c084fc'; // Purple
      else if (type === 'PredictedRisk') color = '#ff7300'; // Orange (ML prediction risk)

      elements.push({
        data: {
          id: node.id,
          label: node.label,
          type: type,
          color: color,
          properties: node.properties
        }
      });
    });

    // Map edges
    graphData.edges.forEach(edge => {
      elements.push({
        data: {
          id: edge.data.id,
          source: edge.data.source,
          target: edge.data.target,
          label: edge.data.label
        }
      });
    });

    cy = cytoscape({
      container: document.getElementById('cy'),
      elements: elements,
      style: [
        {
          selector: 'node',
          style: {
            'label': 'data(label)',
            'background-color': 'data(color)',
            'color': '#fff',
            'font-family': 'Outfit, sans-serif',
            'font-size': '11px',
            'text-valign': 'center',
            'text-halign': 'center',
            'width': '65px',
            'height': '65px',
            'text-wrap': 'wrap',
            'text-max-width': '60px',
            'border-width': '2px',
            'border-color': 'rgba(255,255,255,0.15)',
            'overlay-opacity': 0,
            'transition-property': 'background-color, border-color, width, height',
            'transition-duration': '0.2s'
          }
        },
        {
          selector: 'node:selected',
          style: {
            'border-width': '4px',
            'border-color': '#00d2ff',
            'width': '72px',
            'height': '72px'
          }
        },
        {
          selector: 'edge',
          style: {
            'label': 'data(label)',
            'font-family': 'Outfit, sans-serif',
            'font-size': '9px',
            'color': '#94a3b8',
            'text-background-opacity': 0.85,
            'text-background-color': '#0d121f',
            'text-background-padding': '3px',
            'text-background-shape': 'roundrectangle',
            'width': 2,
            'line-color': '#334155',
            'target-arrow-color': '#334155',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            'control-point-step-size': 40
          }
        },
        {
          selector: 'edge:hover',
          style: {
            'line-color': '#00d2ff',
            'target-arrow-color': '#00d2ff',
            'color': '#fff',
            'width': 3
          }
        }
      ],
      layout: {
        name: 'cose',
        idealEdgeLength: 100,
        nodeOverlap: 20,
        refresh: 20,
        fit: true,
        padding: 30,
        randomize: false,
        componentSpacing: 100,
        nodeRepulsion: 400000,
        edgeElasticity: 100,
        nestingFactor: 5,
        gravity: 80,
        numIter: 1000,
        initialTemp: 200,
        coolingFactor: 0.95,
        minTemp: 1.0
      }
    });

    // Inspector Click Event
    cy.on('tap', 'node', (evt) => {
      const node = evt.target;
      showNodeInspector(node.data());
    });

    // Reset panel if tapped background
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        resetNodeInspector();
      }
    });
  }

  // Cytoscape Toolbar controls
  document.getElementById('btn-fit').addEventListener('click', () => {
    if (cy) cy.fit();
  });
  document.getElementById('btn-layout-cose').addEventListener('click', () => {
    if (cy) {
      cy.layout({ name: 'cose', randomize: true, fit: true }).run();
    }
  });
  document.getElementById('btn-layout-concentric').addEventListener('click', () => {
    if (cy) {
      cy.layout({ name: 'concentric', fit: true, padding: 30 }).run();
    }
  });
  document.getElementById('btn-refresh-graph').addEventListener('click', () => {
    initOrUpdateGraph();
    fetchTripleCount();
  });

  // Node Inspector Logic
  function showNodeInspector(nodeData) {
    document.getElementById('inspector-node-title').innerText = nodeData.label;
    
    const typeBadge = document.getElementById('inspector-node-type');
    typeBadge.innerText = nodeData.type;
    typeBadge.style.display = 'inline-block';
    
    // Choose badge style
    let badgeClass = 'badge ';
    if (nodeData.type === 'Disaster') badgeClass += 'red';
    else if (nodeData.type === 'Location') badgeClass += 'green';
    else if (nodeData.type === 'Agency') badgeClass += 'primary';
    else if (nodeData.type === 'Resource') badgeClass += 'yellow';
    else if (nodeData.type === 'Shelter') badgeClass += 'orange';
    else badgeClass += 'yellow';
    typeBadge.className = badgeClass;

    // Render properties table
    const props = nodeData.properties || {};
    let html = '<table class="prop-table">';
    html += `<tr><td class="prop-name">URI</td><td class="prop-value">${nodeData.id}</td></tr>`;
    
    for (const [key, val] of Object.entries(props)) {
      html += `<tr><td class="prop-name">${key}</td><td class="prop-value">${val}</td></tr>`;
    }
    
    html += '</table>';
    document.getElementById('inspector-properties').innerHTML = html;
  }

  function resetNodeInspector() {
    document.getElementById('inspector-node-title').innerText = 'Select a Node';
    document.getElementById('inspector-node-type').style.display = 'none';
    document.getElementById('inspector-properties').innerHTML = `
      <p style="color: var(--text-secondary); font-size: 0.9rem; font-style: italic;">
        Click on any node to inspect its RDF attributes and relations.
      </p>
    `;
  }

  // SPARQL Playground

  function initSparqlPlayground() {
    const editor = document.getElementById('sparql-editor');
    const select = document.getElementById('template-select');
    
    // Set initial template
    editor.value = queries['all-triples'];

    select.addEventListener('change', () => {
      editor.value = queries[select.value] || '';
    });

    document.getElementById('btn-run-query').addEventListener('click', () => {
      const queryText = editor.value;
      runSparqlQuery(queryText);
    });
  }

  function runSparqlQuery(queryText) {
    const btn = document.getElementById('btn-run-query');
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Running...';
    btn.disabled = true;

    // Reset results layout to show status
    document.getElementById('results-headers').innerHTML = '<th>Running...</th>';
    document.getElementById('results-body').innerHTML = '<tr><td>Executing SPARQL against Fuseki...</td></tr>';
    document.getElementById('results-count').innerText = 'Running...';

    fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: queryText })
    })
    .then(res => {
      if (!res.ok) {
        return res.json().then(errData => {
          throw new Error(errData.error || errData.message || "Server returned status " + res.status);
        });
      }
      return res.json();
    })
    .then(data => {
      btn.innerHTML = '<i class="fa-solid fa-play"></i> Run Query';
      btn.disabled = false;
      renderQueryResults(data);
    })
    .catch(err => {
      btn.innerHTML = '<i class="fa-solid fa-play"></i> Run Query';
      btn.disabled = false;
      
      // Render the error directly inside the results table
      document.getElementById('results-headers').innerHTML = '<th style="color:var(--color-danger);">Query Error</th>';
      document.getElementById('results-body').innerHTML = `<tr><td style="color:var(--color-danger); font-family:monospace; white-space:pre-wrap;">${err.message}</td></tr>`;
      document.getElementById('results-count').innerText = 'Error';
      console.error(err);
    });
  }

  function renderQueryResults(data) {
    const headersTr = document.getElementById('results-headers');
    const bodyTbody = document.getElementById('results-body');
    const countSpan = document.getElementById('results-count');

    headersTr.innerHTML = '';
    bodyTbody.innerHTML = '';

    if (!data || data.length === 0) {
      headersTr.innerHTML = '<th>No Results</th>';
      bodyTbody.innerHTML = '<tr><td>The query executed successfully but returned 0 rows.</td></tr>';
      countSpan.innerText = '0 rows returned';
      return;
    }

    countSpan.innerText = `${data.length} rows returned`;

    // Extract headers from keys of the first object
    const headers = Object.keys(data[0]);
    headers.forEach(h => {
      const th = document.createElement('th');
      th.innerText = h;
      headersTr.appendChild(th);
    });

    // Populate rows
    data.forEach(row => {
      const tr = document.createElement('tr');
      headers.forEach(h => {
        const td = document.createElement('td');
        const value = row[h] || '';
        
        // Make URIs clickable/pretty
        if (value.startsWith('http://') || value.startsWith('https://')) {
          const shortName = value.split('#')[1] || value.substring(value.lastIndexOf('/') + 1);
          td.innerHTML = `<a href="#" class="uri-link" title="${value}">${shortName}</a>`;
          td.querySelector('a').addEventListener('click', (e) => {
            e.preventDefault();
            // Switch to graph and highlight node
            document.getElementById('nav-graph-btn').click();
            setTimeout(() => {
              if (cy) {
                const node = cy.getElementById(value);
                if (node.length > 0) {
                  cy.select().unselect();
                  node.select();
                  cy.zoom(2);
                  cy.center(node);
                  showNodeInspector(node.data());
                }
              }
            }, 500);
          });
        } else {
          td.innerText = value;
        }
        tr.appendChild(td);
      });
      bodyTbody.appendChild(tr);
    });
  }

  // Emergency Ops Room
  function initOpsRoom() {
    // Resource recommendations
    document.getElementById('btn-recommend').addEventListener('click', () => {
      const disaster = document.getElementById('recommend-disaster-select').value;
      runRecommendation(disaster);
    });

    // Route calculations
    document.getElementById('btn-route').addEventListener('click', () => {
      const start = document.getElementById('route-start-select').value;
      const end = document.getElementById('route-end-select').value;
      const avoid = document.getElementById('route-avoid-blocked').checked;
      calculateRoute(start, end, avoid);
    });
  }

  function runRecommendation(disasterUri) {
    const resultsBox = document.getElementById('recommend-results');
    resultsBox.innerHTML = '<span class="text-secondary"><i class="fa-solid fa-spinner fa-spin"></i> Analyzing capabilities...</span>';

    fetch(`/api/recommendations?disaster=${encodeURIComponent(disasterUri)}`)
      .then(res => res.json())
      .then(data => {
        if (!data || data.length === 0) {
          resultsBox.innerHTML = '<span class="badge red">No capable agencies found active in matching districts</span>';
          return;
        }

        let html = '';
        data.forEach(item => {
          html += `
            <div style="border-bottom: 1px solid rgba(255,255,255,0.05); padding-bottom: 0.75rem; margin-bottom: 0.75rem;">
              <div style="display:flex; justify-content:space-between; align-items:center; font-weight:600; color:var(--color-primary);">
                <span>${item.agencyName}</span>
                <span class="badge orange" style="font-size:0.7rem;">Active Base: ${item.location}</span>
              </div>
              <div style="font-size: 0.8rem; color: var(--text-secondary); margin-top: 0.25rem;">
                Provides matching resources: 
                ${item.resourcesProvided.map(r => `<span class="badge yellow" style="color:#000; font-weight:500; font-size:0.7rem; margin-right:3px;">${r}</span>`).join('')}
              </div>
            </div>
          `;
        });
        resultsBox.innerHTML = html;
      })
      .catch(err => {
        resultsBox.innerHTML = '<span class="text-danger">Failed to execute recommendations query.</span>';
        console.error(err);
      });
  }

  function calculateRoute(start, end, avoidBlocked) {
    const resultsBox = document.getElementById('route-results');
    resultsBox.innerHTML = '<span class="text-secondary"><i class="fa-solid fa-spinner fa-spin"></i> Traversing RDF road network...</span>';

    fetch(`/api/routes?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}&avoidBlocked=${avoidBlocked}`)
      .then(res => res.json())
      .then(data => {
        if (!data.success) {
          resultsBox.innerHTML = `<span class="badge red">${data.message}</span>`;
          return;
        }

        let html = '';
        html += `<div style="font-weight: 600; color: var(--color-success); margin-bottom: 0.5rem;">`;
        html += `<i class="fa-solid fa-square-check"></i> Safe Path Found (${data.totalDistance.toFixed(1)} km)</div>`;
        
        data.friendlyPath.forEach((step, idx) => {
          html += `
            <div class="route-step">
              <span class="badge orange" style="font-size:0.75rem;">${idx + 1}</span>
              <span style="color:#fff; font-weight:500;">${step}</span>
              ${idx < data.friendlyPath.length - 1 ? '<span class="route-arrow">→</span>' : ''}
            </div>
          `;
        });

        resultsBox.innerHTML = html;
      })
      .catch(err => {
        resultsBox.innerHTML = '<span class="text-danger">Error calculating route path.</span>';
        console.error(err);
      });
  }

  // Predictive Simulation & Weather
  function initPredictiveWeather() {
    // Select City weather event
    const weatherSelect = document.getElementById('weather-city-select');
    weatherSelect.addEventListener('change', () => {
      fetchWeather(weatherSelect.value);
    });
    fetchWeather('Chennai'); // Initial weather fetch

    // Simulation Sliders Events
    const sliders = [
      { id: 'slider-rainfall', labelId: 'label-rainfall', suffix: ' mm' },
      { id: 'slider-water', labelId: 'label-water', suffix: ' m' },
      { id: 'slider-seismic', labelId: 'label-seismic', suffix: ' g' }
    ];

    sliders.forEach(s => {
      const slider = document.getElementById(s.id);
      const label = document.getElementById(s.labelId);
      slider.addEventListener('input', () => {
        label.innerText = slider.value + s.suffix;
      });
    });

    // Run prediction button
    document.getElementById('btn-predict').addEventListener('click', () => {
      runSimulation();
    });
  }

  function fetchWeather(cityName) {
    fetch(`/api/weather?city=${cityName}`)
      .then(res => res.json())
      .then(data => {
        document.getElementById('weather-city-name').innerText = data.city;
        document.getElementById('weather-temp').innerText = data.temperature;
        document.getElementById('weather-cond').innerText = data.condition;
        document.getElementById('weather-wind').innerText = data.windSpeed;
        document.getElementById('weather-humid').innerText = data.humidity;
        document.getElementById('weather-msg').innerText = data.alertMessage;

        const badge = document.getElementById('weather-alert-badge');
        badge.innerText = `${data.alertLevel} Alert`;
        
        let colorClass = 'badge ';
        if (data.alertLevel.toLowerCase() === 'orange') colorClass += 'orange';
        else if (data.alertLevel.toLowerCase() === 'yellow') colorClass += 'yellow';
        else if (data.alertLevel.toLowerCase() === 'red') colorClass += 'red';
        else colorClass += 'green';
        badge.className = colorClass;
      })
      .catch(err => console.error('Error fetching weather:', err));
  }

  function runSimulation() {
    const city = document.getElementById('predict-city-select').value;
    const rainfall = document.getElementById('slider-rainfall').value;
    const water = document.getElementById('slider-water').value;
    const seismic = document.getElementById('slider-seismic').value;

    const panel = document.getElementById('prediction-result-panel');
    panel.style.display = 'block';
    panel.innerHTML = '<span><i class="fa-solid fa-spinner fa-spin"></i> Processing rules & inserting triples...</span>';

    fetch('/api/predict', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        city: city,
        rainfall: parseFloat(rainfall),
        waterLevel: parseFloat(water),
        seismicActivity: parseFloat(seismic)
      })
    })
    .then(res => res.json())
    .then(data => {
      // Set panel styling based on risk level
      panel.className = `prediction-alert ${data.riskLevel}`;
      panel.innerHTML = `
        <div style="font-weight: bold; color: #fff;">
          <i class="fa-solid fa-circle-exclamation"></i> Simulated Risk Level: ${data.riskLevel}
        </div>
        <div style="font-size: 0.85rem; color: var(--text-primary); margin-top: 0.25rem;">
          ${data.cause}
        </div>
        <div style="font-size: 0.75rem; color: var(--color-primary); margin-top: 0.5rem; font-family: monospace; border-top: 1px solid rgba(255,255,255,0.05); padding-top: 0.4rem;">
          Added Triple: &lt;${data.city}&gt; :hasPredictedRisk &lt;${data.riskUri.split('#')[1]}&gt;
        </div>
      `;

      // Update active triples count
      fetchTripleCount();
      
      // Update graph if cy is initialized
      if (document.getElementById('knowledge-graph').classList.contains('active')) {
        initOrUpdateGraph();
      }
    })
    .catch(err => {
      panel.className = 'prediction-alert red';
      panel.innerText = 'Failed to execute rules and save prediction.';
      console.error(err);
    });
  }
});
