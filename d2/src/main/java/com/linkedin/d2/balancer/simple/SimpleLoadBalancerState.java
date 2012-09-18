/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.balancer.simple;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.Callbacks;
import com.linkedin.common.callback.SimpleCallback;
import com.linkedin.common.util.None;
import com.linkedin.d2.balancer.LoadBalancerState;
import com.linkedin.d2.balancer.LoadBalancerStateItem;
import com.linkedin.d2.balancer.clients.TrackerClient;
import com.linkedin.d2.balancer.properties.ClusterProperties;
import com.linkedin.d2.balancer.properties.PartitionData;
import com.linkedin.d2.balancer.properties.ServiceProperties;
import com.linkedin.d2.balancer.properties.UriProperties;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategy;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategyFactory;
import com.linkedin.d2.balancer.util.ClientFactoryProvider;
import com.linkedin.d2.balancer.util.LoadBalancerUtil;
import com.linkedin.d2.balancer.util.partitions.PartitionAccessor;
import com.linkedin.d2.balancer.util.partitions.PartitionAccessorFactory;
import com.linkedin.d2.discovery.event.PropertyEventBus;
import com.linkedin.d2.discovery.event.PropertyEventBusImpl;
import com.linkedin.d2.discovery.event.PropertyEventPublisher;
import com.linkedin.d2.discovery.event.PropertyEventSubscriber;
import com.linkedin.d2.discovery.event.PropertyEventThread;
import com.linkedin.d2.discovery.event.PropertyEventThread.PropertyEvent;
import com.linkedin.d2.discovery.event.PropertyEventThread.PropertyEventShutdownCallback;
import com.linkedin.r2.transport.common.TransportClientFactory;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.util.ClosableQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.linkedin.d2.discovery.util.LogUtil.debug;
import static com.linkedin.d2.discovery.util.LogUtil.info;
import static com.linkedin.d2.discovery.util.LogUtil.trace;
import static com.linkedin.d2.discovery.util.LogUtil.warn;

public class SimpleLoadBalancerState implements LoadBalancerState, ClientFactoryProvider
{
  private static final Logger                                                            _log =
                                                                                                  LoggerFactory.getLogger(SimpleLoadBalancerState.class);

  private final UriLoadBalancerSubscriber                                                _uriSubscriber;
  private final ClusterLoadBalancerSubscriber                                            _clusterSubscriber;
  private final ServiceLoadBalancerSubscriber                                            _serviceSubscriber;

  private final PropertyEventBus<UriProperties>                                          _uriBus;
  private final PropertyEventBus<ClusterProperties>                                      _clusterBus;
  private final PropertyEventBus<ServiceProperties>                                      _serviceBus;

  private final Map<String, LoadBalancerStateItem<UriProperties>>                        _uriProperties;
  private final Map<String, ClusterInfoItem>                                                 _clusterInfo;
  private final Map<String, LoadBalancerStateItem<ServiceProperties>>                    _serviceProperties;

  private final AtomicLong                                                               _version;

  private final Map<String, Set<String>>                                                 _servicesPerCluster;
  private final PropertyEventThread                                                      _thread;
  private final List<SimpleLoadBalancerStateListener>                                    _listeners;

  /**
   * Map from cluster name => uri => tracker client. For example, sna-1 =>
   * http://ela4-b312.prod.linkedin.com:5432 => tracker client.
   */
  private final Map<String, Map<URI, TrackerClient>>                                     _trackerClients;

  /**
   * Map from clusterName => schemeName.toLowerCase() => TransportClient
   */
  private final Map<String, Map<String, TransportClient>> _clusterClients;

  /**
   * Map from scheme => client factory. For example, http => HttpClientFactory.
   */
  private final Map<String, TransportClientFactory>                                      _clientFactories;

  /**
   * Map from load balancer name => load balancer factory. For example, degrader =>
   * DegraderLoadBalancerStrategyFactory.
   */
  private final Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> _loadBalancerStrategyFactories;

  /**
   * Map from service name => scheme => load balancer strategy. For example, browsemaps =>
   * http => degrader.
   */
  private final Map<String, Map<String, LoadBalancerStrategy>>                           _serviceStrategies;

  /**
   * Map from service name => list of scheme, strategy pairs
   * This is a lazily-populated cache of the results from getStrategiesForService()
   */
  private final Map<String, List<SchemeStrategyPair>>                                   _serviceStrategiesCache;

  // we put together the cluster properties and the partition accessor for a cluster so that we don't have to
  // maintain two seperate maps (which have to be in sync all the time)
  private class ClusterInfoItem
  {
    private final LoadBalancerStateItem<ClusterProperties> _clusterPropertiesItem;
    private final LoadBalancerStateItem<PartitionAccessor> _partitionAccessorItem;

    ClusterInfoItem(ClusterProperties clusterProperties, PartitionAccessor partitionAccessor)
    {
      long version = _version.incrementAndGet();
      _clusterPropertiesItem = new LoadBalancerStateItem<ClusterProperties>(clusterProperties,
                                                                            version,
                                                                            System.currentTimeMillis());
      _partitionAccessorItem = new LoadBalancerStateItem<PartitionAccessor>(partitionAccessor,
                                                                            version,
                                                                            System.currentTimeMillis());
    }

    LoadBalancerStateItem<ClusterProperties> getClusterPropertiesItem()
    {
      return _clusterPropertiesItem;
    }

    LoadBalancerStateItem<PartitionAccessor> getPartitionAccessorItem()
    {
      return _partitionAccessorItem;
    }

    @Override
    public String toString()
    {
      return "_clusterProperties = " + _clusterPropertiesItem.getProperty();
    }
  }

  /*
   * Concurrency considerations:
   *
   * Immutable: _clientFactories _loadBalancerStrategyFactories
   *
   * All event bus callbacks occur on a single thread. The following are mutated only
   * within event bus callbacks, but may be read from any thread at any time:
   * _uriProperties _clusterProperties _serviceProperties _servicesPerCluster
   * _trackerClients _serviceStrategies
   */

  @SuppressWarnings("unchecked")
  public SimpleLoadBalancerState(PropertyEventThread thread,
                                 PropertyEventPublisher<UriProperties> uriPublisher,
                                 PropertyEventPublisher<ClusterProperties> clusterPublisher,
                                 PropertyEventPublisher<ServiceProperties> servicePublisher,
                                 Map<String, TransportClientFactory> clientFactories,
                                 Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> loadBalancerStrategyFactories)
  {
    this(thread,
         new PropertyEventBusImpl<UriProperties>(thread, uriPublisher),
         new PropertyEventBusImpl<ClusterProperties>(thread, clusterPublisher),
         new PropertyEventBusImpl<ServiceProperties>(thread, servicePublisher),
         clientFactories,
         loadBalancerStrategyFactories);
  }

  public SimpleLoadBalancerState(PropertyEventThread thread,
                                 PropertyEventBus<UriProperties> uriBus,
                                 PropertyEventBus<ClusterProperties> clusterBus,
                                 PropertyEventBus<ServiceProperties> serviceBus,
                                 Map<String, TransportClientFactory> clientFactories,
                                 Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> loadBalancerStrategyFactories)
  {
    _thread = thread;
    _uriProperties =
        new ConcurrentHashMap<String, LoadBalancerStateItem<UriProperties>>();
    _clusterInfo =
        new ConcurrentHashMap<String, ClusterInfoItem>();
    _serviceProperties =
        new ConcurrentHashMap<String, LoadBalancerStateItem<ServiceProperties>>();
    _version = new AtomicLong(0);

    _uriBus = uriBus;
    _uriSubscriber = new UriLoadBalancerSubscriber(uriBus);

    _clusterBus = clusterBus;
    _clusterSubscriber = new ClusterLoadBalancerSubscriber(clusterBus);

    _serviceBus = serviceBus;
    _serviceSubscriber = new ServiceLoadBalancerSubscriber(serviceBus);

    // We assume the factories themselves are immutable, therefore a shallow copy of the
    // maps
    // should be a completely immutable data structure.
    _clientFactories =
        Collections.unmodifiableMap(new HashMap<String, TransportClientFactory>(clientFactories));
    _loadBalancerStrategyFactories =
        Collections.unmodifiableMap(new HashMap<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>>(loadBalancerStrategyFactories));

    _servicesPerCluster = new ConcurrentHashMap<String, Set<String>>();
    _serviceStrategies =
        new ConcurrentHashMap<String, Map<String, LoadBalancerStrategy>>();
    _serviceStrategiesCache =
        new ConcurrentHashMap<String, List<SchemeStrategyPair>>();
    _trackerClients = new ConcurrentHashMap<String, Map<URI, TrackerClient>>();
    _clusterClients = new ConcurrentHashMap<String, Map<String, TransportClient>>();
    _listeners =
        Collections.synchronizedList(new ArrayList<SimpleLoadBalancerStateListener>());
  }

  public void register(final SimpleLoadBalancerStateListener listener)
  {
    trace(_log, "register listener: ", listener);

    _thread.send(new PropertyEvent("add listener for state")
    {
      @Override
      public void run()
      {
        _listeners.add(listener);
      }
    });
  }

  public void unregister(final SimpleLoadBalancerStateListener listener)
  {
    trace(_log, "unregister listener: ", listener);

    _thread.send(new PropertyEvent("remove listener for state")
    {
      @Override
      public void run()
      {
        _listeners.remove(listener);
      }
    });
  }

  @Override
  public void start(final Callback<None> callback)
  {
    callback.onSuccess(None.none());
  }

  public void shutdown(final PropertyEventShutdownCallback shutdown)
  {
    trace(_log, "shutdown");

    // shutdown all three registries, all tracker clients, and the event thread
    _thread.send(new PropertyEvent("shutdown load balancer state")
    {
      @Override
      public void run()
      {
        // put all tracker clients into a single set for convenience
        Set<TransportClient> transportClients = new HashSet<TransportClient>();

        for (Map<String, TransportClient> clientsByScheme : _clusterClients.values())
        {
          transportClients.addAll(clientsByScheme.values());
        }

        Callback<None> trackerCallback = Callbacks.countDown(Callbacks.<None>adaptSimple(new SimpleCallback()
        {
          @Override
          public void onDone()
          {
            shutdown.done();
          }
        }), transportClients.size());


        info(_log, "shutting down cluster clients");

        for (TransportClient transportClient : transportClients)
        {
          transportClient.shutdown(trackerCallback);
        }
      }
    });
  }

  public void listenToService(final String serviceName,
                              final LoadBalancerStateListenerCallback callback)
  {
    trace(_log, "listenToService: ", serviceName);

    _serviceSubscriber.ensureListening(serviceName, callback);
  }

  public void listenToCluster(final String clusterName,
                              final LoadBalancerStateListenerCallback callback)
  {
    trace(_log, "listenToCluster: ", clusterName);

    // wrap the callback since we need to wait for both uri and cluster listeners to
    // onInit before letting the callback know that we're done.
    final LoadBalancerStateListenerCallback wrappedCallback =
        new LoadBalancerStateListenerCallback()
        {
          private AtomicInteger _count = new AtomicInteger(2);

          @Override
          public void done(int type, String name)
          {
            if (_count.decrementAndGet() <= 0)
            {
              callback.done(type, clusterName);
            }
          }
        };

    _clusterSubscriber.ensureListening(clusterName, wrappedCallback);
    _uriSubscriber.ensureListening(clusterName, wrappedCallback);
  }

  public LoadBalancerStateItem<UriProperties> getUriProperties(String clusterName)
  {
    return _uriProperties.get(clusterName);
  }

  public LoadBalancerStateItem<ClusterProperties> getClusterProperties(String clusterName)
  {
    ClusterInfoItem clusterInfoItem =  _clusterInfo.get(clusterName);
    return clusterInfoItem == null ? null : clusterInfoItem.getClusterPropertiesItem();
  }

  public LoadBalancerStateItem<PartitionAccessor> getPartitionAccessor(String clusterName)
  {
    ClusterInfoItem clusterInfoItem =  _clusterInfo.get(clusterName);
    return clusterInfoItem == null ? null : clusterInfoItem.getPartitionAccessorItem();
  }

  public LoadBalancerStateItem<ServiceProperties> getServiceProperties(String serviceName)
  {
    return _serviceProperties.get(serviceName);
  }

  public Map<String, LoadBalancerStateItem<ServiceProperties>> getServiceProperties()
  {
    return _serviceProperties;
  }

  public long getVersion()
  {
    return _version.get();
  }

  public int getClusterCount()
  {
    return _clusterInfo.size();
  }

  public int getClusterListenCount()
  {
    return _clusterSubscriber.propertyListenCount();
  }

  public int getListenerCount()
  {
    return _listeners.size();
  }

  public int getServiceCount()
  {
    return _serviceProperties.size();
  }

  public int getServiceListenCount()
  {
    return _serviceSubscriber.propertyListenCount();
  }

  public Set<String> getSupportedSchemes()
  {
    return _clientFactories.keySet();
  }

  public Set<String> getSupportedStrategies()
  {
    return _loadBalancerStrategyFactories.keySet();
  }

  public int getTrackerClientCount(String clusterName)
  {
    return LoadBalancerUtil.getOrElse(_trackerClients,
                                      clusterName,
                                      new HashMap<URI, TrackerClient>()).size();
  }

  public int getUriCount()
  {
    return _uriProperties.size();
  }

  public void setVersion(final long version)
  {
    trace(_log, "setVersion: ", version);

    _thread.send(new PropertyEvent("set version to: " + version)
    {
      @Override
      public void run()
      {
        info(_log, "set global version to: ", version);

        _version.set(version);
      }
    });
  }

  public boolean isListeningToCluster(String clusterName)
  {
    return _clusterSubscriber.isListeningToProperty(clusterName);
  }

  public boolean isListeningToService(String serviceName)
  {
    return _serviceSubscriber.isListeningToProperty(serviceName);
  }

  public TrackerClient getClient(String clusterName, URI uri)
  {
    Map<URI, TrackerClient> trackerClients = _trackerClients.get(clusterName);
    TrackerClient trackerClient = null;

    if (trackerClients != null)
    {
      trackerClient = trackerClients.get(uri);
    }
    else
    {
      warn(_log, "get client called on unknown cluster ", clusterName, ": ", uri);
    }

    return trackerClient;
  }

  @Override
  public TransportClient getClient(String clusterName, String scheme)
  {
    Map<String, TransportClient> transportClients = _clusterClients.get(clusterName);
    TransportClient transportClient = null;

    if (transportClients != null)
    {
      transportClient = transportClients.get(scheme.toLowerCase());
      if (transportClient == null)
      {
        warn(_log, "no generic transport client for cluster " + clusterName +
                " and scheme: " + scheme);
      }
    }
    else
    {
      warn(_log, "get client called on unknown cluster ", clusterName);
    }
    return transportClient;
  }

  public LoadBalancerStrategy getStrategy(String serviceName, String scheme)
  {
    Map<String, LoadBalancerStrategy> strategies = _serviceStrategies.get(serviceName);
    LoadBalancerStrategy strategy = null;

    if (strategies != null)
    {
      strategy = strategies.get(scheme);
    }
    else
    {
      warn(_log, "get strategy called on unknown service ", serviceName);
    }

    return strategy;
  }

  @Override
  public List<SchemeStrategyPair> getStrategiesForService(String serviceName,
                                                           List<String> prioritizedSchemes)
  {
    List<SchemeStrategyPair> cached = _serviceStrategiesCache.get(serviceName);
    if (cached != null)
    {
      return cached;
    }
    else
    {

      List<SchemeStrategyPair> orderedStrategies = new ArrayList<SchemeStrategyPair>(prioritizedSchemes.size());
      for (String scheme : prioritizedSchemes)
      {
        // get the strategy for this service and scheme
        LoadBalancerStrategy strategy = getStrategy(serviceName, scheme);

        if (strategy != null)
        {
          orderedStrategies.add(new SchemeStrategyPair(scheme, strategy));
        }
        else
        {
          warn(_log,
               "unable to find a load balancer strategy for ",
               serviceName,
               " with scheme: ",
               scheme);
        }
      }
      _serviceStrategiesCache.put(serviceName, orderedStrategies);
      return orderedStrategies;
    }
  }

  @Override
  public TransportClientFactory getClientFactory(String scheme)
  {
    return _clientFactories.get(scheme);
  }

  public abstract class AbstractLoadBalancerSubscriber<T> implements
      PropertyEventSubscriber<T>
  {
    private final String                                                                  _name;
    private final int                                                                     _type;
    private final PropertyEventBus<T>                                                     _eventBus;
    private final ConcurrentMap<String, ClosableQueue<LoadBalancerStateListenerCallback>> _waiters =
                                                                                                       new ConcurrentHashMap<String, ClosableQueue<LoadBalancerStateListenerCallback>>();

    public AbstractLoadBalancerSubscriber(int type, PropertyEventBus<T> eventBus)
    {
      _name = this.getClass().getSimpleName();
      _type = type;
      _eventBus = eventBus;
    }

    public boolean isListeningToProperty(String propertyName)
    {
      ClosableQueue<LoadBalancerStateListenerCallback> waiters =
          _waiters.get(propertyName);
      return waiters != null && waiters.isClosed();
    }

    public int propertyListenCount()
    {
      return _waiters.size();
    }

    public void ensureListening(String propertyName,
                                LoadBalancerStateListenerCallback callback)
    {
      ClosableQueue<LoadBalancerStateListenerCallback> waiters =
          _waiters.get(propertyName);
      boolean register = false;
      if (waiters == null)
      {
        waiters = new ClosableQueue<LoadBalancerStateListenerCallback>();
        ClosableQueue<LoadBalancerStateListenerCallback> previous =
            _waiters.putIfAbsent(propertyName, waiters);
        if (previous == null)
        {
          // We are the very first to register
          register = true;
        }
        else
        {
          // Someone else beat us to it
          waiters = previous;
        }
      }
      // Ensure the callback is enqueued before registering with the bus
      if (!waiters.offer(callback))
      {
        callback.done(_type, propertyName);
      }
      if (register)
      {
        _eventBus.register(Collections.singleton(propertyName), this);
      }
    }

    @Override
    public void onAdd(final String propertyName, final T propertyValue)
    {
      trace(_log, _name, ".onAdd: ", propertyName, ": ", propertyValue);

      handlePut(propertyName, propertyValue);
    }

    @Override
    public void onInitialize(final String propertyName, final T propertyValue)
    {
      trace(_log, _name, ".onInitialize: ", propertyName, ": ", propertyValue);

      handlePut(propertyName, propertyValue);

      for (LoadBalancerStateListenerCallback waiter : _waiters.get(propertyName).close())
      {
        waiter.done(_type, propertyName);
      }
    }

    @Override
    public void onRemove(final String propertyName)
    {
      trace(_log, _name, ".onRemove: ", propertyName);

      handleRemove(propertyName);
    }

    protected abstract void handlePut(String propertyName, T propertyValue);

    protected abstract void handleRemove(String name);
  }

  public class UriLoadBalancerSubscriber extends
      AbstractLoadBalancerSubscriber<UriProperties>
  {
    public UriLoadBalancerSubscriber(PropertyEventBus<UriProperties> uPropertyEventBus)
    {
      super(LoadBalancerStateListenerCallback.CLUSTER, uPropertyEventBus);
    }

    @Override
    protected void handlePut(final String listenTo, final UriProperties discoveryProperties)
    {
      // add tracker clients for uris that we aren't already tracking
      if (discoveryProperties != null)
      {
        Map<URI, TrackerClient> trackerClients =
            _trackerClients.get(discoveryProperties.getClusterName());

        if (trackerClients == null)
        {
          trackerClients = new ConcurrentHashMap<URI, TrackerClient>();
          _trackerClients.put(discoveryProperties.getClusterName(), trackerClients);
        }

        for (URI uri : discoveryProperties.Uris())
        {
          if (!trackerClients.containsKey(uri))
          {
            TrackerClient client = getTrackerClient(discoveryProperties.getClusterName(),
                uri,
                discoveryProperties.getPartitionDataMap(uri));

            if (client != null)
            {
              info(_log,
                   "adding new tracker client from updated uri properties: ",
                   client);

              // notify listeners of the added client
              for (SimpleLoadBalancerStateListener listener : _listeners)
              {
                listener.onClientAdded(listenTo, client);
              }

              trackerClients.put(uri, client);
            }
          }
        }
      }

      // replace the URI properties
      _uriProperties.put(listenTo,
                         new LoadBalancerStateItem<UriProperties>(discoveryProperties,
                                                                  _version.incrementAndGet(),
                                                                  System.currentTimeMillis()));

      // now remove URIs that we're tracking, but have been removed from the new uri
      // properties
      if (discoveryProperties != null)
      {
        Map<URI, TrackerClient> trackerClients =
            _trackerClients.get(discoveryProperties.getClusterName());

        if (trackerClients != null)
        {
          for (Iterator<URI> it = trackerClients.keySet().iterator(); it.hasNext();)
          {
            URI uri = it.next();

            if (!discoveryProperties.Uris().contains(uri))
            {
              TrackerClient client = trackerClients.remove(uri);

              info(_log, "removing dead tracker client: ", client);

              // notify listeners of the removed client
              for (SimpleLoadBalancerStateListener listener : _listeners)
              {
                listener.onClientRemoved(listenTo, client);
              }

              // We don't shut down the dead TrackerClient, because TrackerClients hold no
              // resources and simply point to the common cluster client (from _clusterClients).
            }
          }
        }
      }
      else
      {
        // uri properties was null, so remove all tracker clients
        warn(_log, "removing all tracker clients for cluster: ", listenTo);

        Map<URI, TrackerClient> clients = _trackerClients.remove(listenTo);

        if (clients != null)
        {
          for (TrackerClient client : clients.values())
          {
            // notify listeners of the removed client
            for (SimpleLoadBalancerStateListener listener : _listeners)
            {
              listener.onClientRemoved(listenTo, client);
            }
          }
        }
      }
    }

    @Override
    protected void handleRemove(final String listenTo)
    {
      _uriProperties.remove(listenTo);
    }
  }

  public class ClusterLoadBalancerSubscriber extends
      AbstractLoadBalancerSubscriber<ClusterProperties>
  {

    public ClusterLoadBalancerSubscriber(PropertyEventBus<ClusterProperties> cPropertyEventBus)
    {
      super(LoadBalancerStateListenerCallback.CLUSTER, cPropertyEventBus);
    }

    @Override
    protected void handlePut(final String listenTo, final ClusterProperties discoveryProperties)
    {
      if (discoveryProperties != null)
      {
        _clusterInfo.put(listenTo, new ClusterInfoItem(discoveryProperties,
            PartitionAccessorFactory.getPartitionAccessor(discoveryProperties.getPartitionProperties())));
        // update all tracker clients to use new cluster configs
        LoadBalancerStateItem<UriProperties> uriItem =
            _uriProperties.get(discoveryProperties.getClusterName());

        final String clusterName = discoveryProperties.getClusterName();

        ClusterInfoItem clusterInfoItem =
            _clusterInfo.get(clusterName);
        Map<String, String> clusterProperties = Collections.emptyMap();

        if (clusterInfoItem != null)
        {
          // clusterInfoItem.getClusterPropertiesItem can not be null because it's always created in construction
          ClusterProperties clusterProperty = clusterInfoItem.getClusterPropertiesItem().getProperty();

          if (clusterProperty != null)
          {
            Map<String, String> props = clusterProperty.getProperties();
            if (props != null)
            {
              clusterProperties = props;
            }
          }
          else
          {
            debug(_log, "got null property item");
          }
        }
        List<String> schemes = discoveryProperties.getPrioritizedSchemes();
        Map<String,TransportClient> newClusterClients = new HashMap<String, TransportClient>();
        for (String scheme : schemes)
        {
          TransportClientFactory factory = _clientFactories.get(scheme);
          if (factory != null)
          {
            TransportClient client = factory.getClient(clusterProperties);
            newClusterClients.put(scheme.toLowerCase(), client);
          }
          else
          {
            _log.warn("Failed to find client factory for scheme {}", scheme);
          }
        }
        // clients-by-scheme map is never edited, only replaced.
        newClusterClients = Collections.unmodifiableMap(newClusterClients);

        // Replace the cluster clients with the newly instantiated map, before we instantiate the
        // the tracker clients. getTrackerClient() will use the cluster client from this map.
        Map<String,TransportClient> oldClusterClients = _clusterClients.put(clusterName, newClusterClients);

        Map<URI,TrackerClient> newTrackerClients;
        UriProperties uriProperties = uriItem == null ? null : uriItem.getProperty();
        if (uriProperties != null)
        {
          Set<URI> uris = uriProperties.Uris();
          // clients-by-uri map may be edited later by UriPropertiesListener.handlePut
          newTrackerClients = new ConcurrentHashMap<URI, TrackerClient>((int)Math.ceil(uris.size() / 0.75f), 0.75f, 1);
          for (URI uri : uris)
          {
            TrackerClient trackerClient = getTrackerClient(clusterName, uri, uriProperties.getPartitionDataMap(uri));
            if (trackerClient != null)
            {
              newTrackerClients.put(uri, trackerClient);
            }
          }
        }
        else
        {
          // clients-by-uri map may be edited later by UriPropertiesListener.handlePut
          newTrackerClients = new ConcurrentHashMap<URI, TrackerClient>(16, 0.75f, 1);
        }

        Map<URI,TrackerClient> oldTrackerClients = _trackerClients.put(clusterName, newTrackerClients);
        // No need to shut down oldTrackerClients, because they all point directly to the TransportClient for the cluster

        // We do need to shut down the old cluster clients
        if (oldClusterClients != null)
        {
          for (TransportClient client : oldClusterClients.values())
          {
            Callback<None> callback = new Callback<None>()
            {
              @Override
              public void onError(Throwable e)
              {
                _log.warn("Failed to shut down old cluster TransportClient", e);
              }

              @Override
              public void onSuccess(None result)
              {
                _log.info("Shut down old cluster TransportClient");
              }
            };
            client.shutdown(callback);
          }
        }

        // refresh all services on this cluster in case the prioritized schemes
        // changed
        Set<String> servicesOnCluster =
            _servicesPerCluster.get(discoveryProperties.getClusterName());

        if (servicesOnCluster != null)
        {
          for (String serviceName : servicesOnCluster)
          {
            LoadBalancerStateItem<ServiceProperties> serviceProperties =
                _serviceProperties.get(serviceName);

            if (serviceProperties != null && serviceProperties.getProperty() != null)
            {
              refreshServiceStrategies(serviceProperties.getProperty());
            }
          }
        }
      }
      else
      {
        // still insert the ClusterInfoItem when discoveryProperties is null, but don't create accessor
        _clusterInfo.put(listenTo, new ClusterInfoItem(discoveryProperties, null));
      }
    }

    @Override
    protected void handleRemove(final String listenTo)
    {
      _clusterInfo.remove(listenTo);
    }
  }

  public class ServiceLoadBalancerSubscriber extends
      AbstractLoadBalancerSubscriber<ServiceProperties>
  {
    public ServiceLoadBalancerSubscriber(PropertyEventBus<ServiceProperties> eventBus)
    {
      super(LoadBalancerStateListenerCallback.SERVICE, eventBus);
    }

    @Override
    protected void handlePut(final String listenTo, final ServiceProperties discoveryProperties)
    {
      LoadBalancerStateItem<ServiceProperties> oldServicePropertiesItem =
          _serviceProperties.get(listenTo);

      _serviceProperties.put(listenTo,
                             new LoadBalancerStateItem<ServiceProperties>(discoveryProperties,
                                                                          _version.incrementAndGet(),
                                                                          System.currentTimeMillis()));

      // in case the load balancer strategy name changed, refresh strategies
      if (discoveryProperties != null)
      {
        refreshServiceStrategies(discoveryProperties);

        // refresh state for which services are on which clusters
        Set<String> serviceNames =
            _servicesPerCluster.get(discoveryProperties.getClusterName());

        if (serviceNames == null)
        {
          serviceNames =
              Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
          _servicesPerCluster.put(discoveryProperties.getClusterName(), serviceNames);
        }

        serviceNames.add(discoveryProperties.getServiceName());
      }
      else if (oldServicePropertiesItem != null)
      {
        // if we've replaced a service properties with null, update the cluster ->
        // service state that the service is no longer on its cluster.
        ServiceProperties oldServiceProperties = oldServicePropertiesItem.getProperty();

        if (oldServiceProperties != null)
        {
          Set<String> serviceNames =
              _servicesPerCluster.get(oldServiceProperties.getClusterName());

          if (serviceNames != null)
          {
            serviceNames.remove(oldServiceProperties.getServiceName());
          }
        }
      }

      if (discoveryProperties == null)
      {
        _log.info("PROPS WERE NULL FOR {}", listenTo);
      }
    }

    @Override
    protected void handleRemove(final String listenTo)
    {
      LoadBalancerStateItem<ServiceProperties> serviceItem =
          _serviceProperties.remove(listenTo);

      if (serviceItem != null && serviceItem.getProperty() != null)
      {
        ServiceProperties serviceProperties = serviceItem.getProperty();

        // remove this service from the cluster -> services map
        Set<String> serviceNames =
            _servicesPerCluster.get(serviceProperties.getClusterName());

        if (serviceNames != null)
        {
          serviceNames.remove(serviceProperties.getServiceName());
        }
      }
    }
  }

  private TrackerClient getTrackerClient(String clusterName, URI uri, Map<Integer, PartitionData> partitionDataMap)
  {
    Map<String,TransportClient> clientsByScheme = _clusterClients.get(clusterName);
    if (clientsByScheme == null)
    {
      _log.error("getTrackerClient: unknown cluster name {} for URI {} and partitionDataMap {}",
          new Object[]{ clusterName, uri, partitionDataMap });
      return null;
    }
    TransportClient client = clientsByScheme.get(uri.getScheme().toLowerCase());
    if (client == null)
    {
      _log.error("getTrackerClient: invalid scheme for cluster {}, URI {} and partitionDataMap {}",
          new Object[]{ clusterName, uri, partitionDataMap });
      return null;
    }
    TrackerClient trackerClient = new TrackerClient(uri, partitionDataMap, client);
    return trackerClient;
  }

  void refreshServiceStrategies(ServiceProperties serviceProperties)
  {
    info(_log, "refreshing service strategies for service: ", serviceProperties);
    List<String> strategyList = serviceProperties.getLoadBalancerStrategyList();
    LoadBalancerStrategyFactory<? extends LoadBalancerStrategy> factory = null;
    if (strategyList != null && !strategyList.isEmpty())
    {
      // In this prioritized strategy list, pick the first one that is available. This is needed
      // so that a new strategy can be used as it becomes available in the client, rather than
      // waiting for all clients to update their code level before any clients can use it.
      for (String strategy : strategyList)
      {
        factory = _loadBalancerStrategyFactories.get(strategy);
        if (factory != null)
        {
          break;
        }
      }
    }
    else
    {
      factory =
          _loadBalancerStrategyFactories.get(serviceProperties.getLoadBalancerStrategyName());
    }
    // if we get here without a factory, then something might be wrong, there should always
    // be at least a default strategy in the list that is always available.
    // The intent is that the loadBalancerStrategyName will be replaced by the
    // loadBalancerStrategyList, and eventually the StrategyName will be removed from the code.
    // We don't issue a RuntimeException here because it's possible, when adding services (ie publishAdd),
    // to refreshServiceStrategies without the strategy existing yet.
    if (factory == null)
    {
      warn(_log,"No valid strategy found. ", serviceProperties);
    }
    ClusterInfoItem clusterInfoItem =
        _clusterInfo.get(serviceProperties.getClusterName());
    Map<String, LoadBalancerStrategy> strategyMap =
        new ConcurrentHashMap<String, LoadBalancerStrategy>();

    if (clusterInfoItem != null && factory != null)
    {
      // clsuterInfoItem.getClusterPropertiesItem can not be null
      ClusterProperties clusterProperty = clusterInfoItem.getClusterPropertiesItem().getProperty();

      if (clusterProperty != null)
      {
        List<String> schemes = clusterProperty.getPrioritizedSchemes();
        if (schemes != null)
        {
          for (String scheme : schemes)
          {
            Map<String, Object> loadBalancerStrategyProperties =
                new HashMap<String, Object>(serviceProperties.getLoadBalancerStrategyProperties());

            LoadBalancerStrategy strategy = factory.newLoadBalancer(
                serviceProperties.getServiceName(),
                loadBalancerStrategyProperties);

            strategyMap.put(scheme, strategy);
          }
        }
        else
        {
          debug(_log,
                "cluster property had null for prioritized schemes: ",
                clusterProperty);
        }
      }
      else
      {
        debug(_log, "property item had null property for: ", serviceProperties);
      }
    }
    else
    {
      warn(_log,
           "unable to find cluster or factory for ",
           serviceProperties,
           ": ",
           clusterInfoItem,
           " ",
           factory);
    }

    Map<String, LoadBalancerStrategy> oldStrategies =
        _serviceStrategies.put(serviceProperties.getServiceName(), strategyMap);
    _serviceStrategiesCache.remove(serviceProperties.getServiceName());

    info(_log,
         "removing strategies ",
         serviceProperties.getServiceName(),
         ": ",
         oldStrategies);

    info(_log,
         "putting strategies ",
         serviceProperties.getServiceName(),
         ": ",
         strategyMap);

    // notify listeners of the removed strategy
    if (oldStrategies != null)
    {
      for (SimpleLoadBalancerStateListener listener : _listeners)
      {
        for (Map.Entry<String, LoadBalancerStrategy> oldStrategy : oldStrategies.entrySet())
        {
          listener.onStrategyRemoved(serviceProperties.getServiceName(),
                                     oldStrategy.getKey(),
                                     oldStrategy.getValue());
        }

      }
    }

    // we need to inform the listeners of the strategy removal before the strategy add, otherwise
    // they will get confused and remove what was just added.
    if (!strategyMap.isEmpty())
    {
      for (SimpleLoadBalancerStateListener listener : _listeners)
      {
        // notify listeners of the added strategy
        for (Map.Entry<String, LoadBalancerStrategy> newStrategy : strategyMap.entrySet())
        {
          listener.onStrategyAdded(serviceProperties.getServiceName(),
                                   newStrategy.getKey(),
                                   newStrategy.getValue());
        }
      }
    }
  }

  public interface SimpleLoadBalancerStateListener
  {
    void onStrategyAdded(String serviceName, String scheme, LoadBalancerStrategy strategy);

    void onStrategyRemoved(String serviceName,
                           String scheme,
                           LoadBalancerStrategy strategy);

    void onClientAdded(String clusterName, TrackerClient client);

    void onClientRemoved(String clusterName, TrackerClient client);
  }

}
